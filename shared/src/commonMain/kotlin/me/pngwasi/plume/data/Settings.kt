package me.pngwasi.plume.data

import io.ktor.http.Url
import kotlinx.serialization.Serializable

/**
 * Wire shape of a provider's HTTP API. Only two, because nearly the whole ecosystem speaks OpenAI's
 * chat-completions format; Gemini is the one common exception worth its own client.
 */
@Serializable
enum class ProviderKind { OpenAiCompatible, Gemini, Anthropic }

@Serializable
enum class ThemeMode { System, Light, Dark }

/**
 * [Low] asks for the least deliberation the provider accepts, since revising or translating a
 * sentence is not a reasoning problem. [ProviderDefault] sends no parameter at all, the escape
 * hatch for endpoints that reject it.
 */
@Serializable
enum class ReasoningMode { Low, ProviderDefault }

/**
 * Which reasoning parameter a provider understands. [Auto] infers it from kind and host, which
 * cannot work for self-hosted proxies speaking another dialect from an anonymous domain — without
 * an override they lose reasoning control silently, since one rejection drops the parameter forever.
 */
@Serializable
enum class ReasoningDialect { Auto, OpenAi, OpenRouter, Gemini }

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
    val reasoning: ReasoningMode = ReasoningMode.Low,
    /** Only meaningful for custom providers; the built-ins are always correctly detected. */
    val reasoningDialect: ReasoningDialect = ReasoningDialect.Auto,
    /** Local runtimes (Ollama, LM Studio, llama.cpp) take any key or none, so a key cannot be forced. */
    val authRequired: Boolean = true,
) {
    /** API keys never live here — they are held encrypted by [SecretStore], keyed by provider id. */
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()
}

/** Loopback and private-range addresses, where a local runtime lives and no API key is wanted. */
fun isLocalEndpoint(baseUrl: String): Boolean {
    val trimmed = baseUrl.trim()
    // Ktor defaults a missing host to "localhost", so non-URL input must be rejected before parsing
    // or an empty field reads as a local endpoint.
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val host = runCatching {
        Url(trimmed).host
    }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    return host == "localhost" ||
        host == "127.0.0.1" ||
        host == "::1" ||
        host == "0.0.0.0" ||
        host.endsWith(".local") ||
        host.startsWith("10.") ||
        host.startsWith("192.168.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
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
    val recents: List<String> = emptyList(),
    /** Skips the picker entirely when set — for people who always translate one way. */
    val defaultTarget: String? = null,
) {
    fun promptOrDefault(): String = systemPrompt.ifBlank { Prompts.TRANSLATE }
}

/**
 * Desktop-only preferences, kept in the shared settings document so there is one file to back up;
 * mobile builds never read them. Hotkey defaults match MyReviser's to preserve muscle memory.
 */
@Serializable
data class DesktopSettings(
    val reviseSelection: String = "",
    /** Selects the whole field first, then revises it. */
    val reviseAll: String = "",
    val translateSelection: String = "",
    val startOnLogin: Boolean = false,
    /**
     * Off, so a launch always puts something on screen. Starting straight into the tray is right
     * once Plume is a habit and wrong before then: the first launch would appear to do nothing, and
     * on a desktop with no visible tray there would be no way back to the window at all.
     */
    val startMinimised: Boolean = false,
    /** Off: the replaced text is its own confirmation, and a banner per revision is a lot. */
    val notifyOnFinish: Boolean = false,
) {
    fun reviseSelectionOrDefault(defaults: HotkeyDefaults) =
        reviseSelection.ifBlank { defaults.reviseSelection }

    fun reviseAllOrDefault(defaults: HotkeyDefaults) = reviseAll.ifBlank { defaults.reviseAll }

    fun translateSelectionOrDefault(defaults: HotkeyDefaults) =
        translateSelection.ifBlank { defaults.translateSelection }
}

/** Per-platform hotkey defaults, since the modifier that is idiomatic differs by desktop. */
data class HotkeyDefaults(
    val reviseSelection: String,
    val reviseAll: String,
    val translateSelection: String,
)

/** Two actions on one binding means one silently never fires, so duplicates are rejected on edit. */
fun duplicateHotkeys(bindings: List<String>): Set<String> {
    val seen = mutableSetOf<String>()
    val duplicates = mutableSetOf<String>()
    bindings.filter { it.isNotBlank() }.forEach { binding ->
        val normalised = normaliseHotkey(binding)
        if (!seen.add(normalised)) duplicates += normalised
    }
    return duplicates
}

/** Order and case must not make two identical bindings look different. */
fun normaliseHotkey(binding: String): String =
    binding.split('+')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .sorted()
        .joinToString("+")

/**
 * A binding needs two keys including a modifier, or it fires on ordinary typing. Modifier-only
 * combinations stay legal because MyReviser shipped them and they work.
 */
fun validateHotkey(binding: String): String? {
    val parts = binding.split('+').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "Enter a shortcut"
    if (parts.size < 2) return "Use at least two keys, such as ctrl+alt+r"
    if (parts.distinct().size != parts.size) return "The same key is listed twice"
    if (parts.none { it in HOTKEY_MODIFIERS }) return "Include a modifier such as ctrl or alt"
    return null
}

val HOTKEY_MODIFIERS = setOf("ctrl", "control", "alt", "option", "shift", "meta", "super", "win", "cmd")

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
    /** Off by default: enabling adds an entry to the system keyboard list, never done unasked. */
    val keyboardEnabled: Boolean = false,
    /**
     * Whether a leading `@provider` routes one request to a named provider. On by default: free
     * when unused, and an unknown mention stays ordinary text rather than being swallowed.
     */
    val providerMentions: Boolean = true,
    /** Ignored on mobile, where there are no hotkeys and no tray. */
    val desktop: DesktopSettings = DesktopSettings(),
) {
    /** Falls back to the default when the override points at a since-deleted provider. */
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
/** Generous on purpose: reasoning models can deliberate a minute or more before the first token. */
const val DEFAULT_TIMEOUT_SECONDS = 120
const val MAX_TIMEOUT_SECONDS = 300
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

/** Per-field so the editor can say which of base URL, model or key is missing. */
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
    apiKey = if (config.authRequired && apiKey.isBlank()) "API key is required" else null,
)

fun List<String>.withRecentTarget(code: String, max: Int = MAX_RECENT_TARGETS): List<String> =
    (listOf(code) + filterNot { it.equals(code, ignoreCase = true) }).take(max)
