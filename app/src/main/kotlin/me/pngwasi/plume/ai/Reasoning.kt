package me.pngwasi.plume.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.pngwasi.plume.data.ProviderKind
import me.pngwasi.plume.data.ReasoningMode
import java.util.Collections

/**
 * How a provider wants to be told to think less.
 *
 * There is no portable parameter for this, and getting it wrong is not silently ignored — OpenAI
 * returns 400 for `reasoning_effort` on a non-reasoning model, OpenRouter returns 400 if it sees
 * both its own shape and OpenAI's, and Gemini refuses a zero thinking budget on models that cannot
 * turn thinking off. So the shape is chosen per provider and, crucially, is recoverable.
 */
enum class ReasoningStyle {
    /** `reasoning_effort: "low"` — OpenAI and most compatible gateways. */
    OpenAiEffort,

    /** `reasoning: { effort, exclude }` — OpenRouter normalises this across every model it serves. */
    OpenRouterReasoning,

    /** `generationConfig.thinkingConfig.thinkingBudget` — Google. */
    GeminiBudget,
}

object Reasoning {

    /**
     * OpenRouter is picked out by host rather than by provider kind, because a custom provider can
     * point at it too and it is the one OpenAI-compatible gateway with its own reasoning shape.
     */
    fun styleFor(kind: ProviderKind, baseUrl: String): ReasoningStyle = when (kind) {
        ProviderKind.Gemini -> ReasoningStyle.GeminiBudget
        ProviderKind.OpenAiCompatible ->
            if (baseUrl.contains("openrouter.ai", ignoreCase = true)) {
                ReasoningStyle.OpenRouterReasoning
            } else {
                ReasoningStyle.OpenAiEffort
            }
    }

    /**
     * Fields to merge into a chat-completions body, empty when nothing should be sent.
     *
     * "low" rather than "none" or "minimal" deliberately: it is the value the widest range of models
     * accept, and some models refuse to have reasoning switched off entirely.
     */
    fun chatFields(style: ReasoningStyle, mode: ReasoningMode): Map<String, kotlinx.serialization.json.JsonElement> {
        if (mode != ReasoningMode.Low) return emptyMap()
        return when (style) {
            ReasoningStyle.OpenAiEffort -> mapOf("reasoning_effort" to JsonPrimitive("low"))
            ReasoningStyle.OpenRouterReasoning -> mapOf(
                "reasoning" to buildJsonObject {
                    put("effort", JsonPrimitive("low"))
                    // Reasoning tokens are billed and useless to us — we only want the rewritten text.
                    put("exclude", JsonPrimitive(true))
                },
            )
            ReasoningStyle.GeminiBudget -> emptyMap()
        }
    }

    /** Gemini carries its control inside `generationConfig`, so it is built separately. */
    fun geminiThinkingConfig(mode: ReasoningMode): JsonObject? {
        if (mode != ReasoningMode.Low) return null
        return buildJsonObject { put("thinkingBudget", JsonPrimitive(0)) }
    }
}

/**
 * Remembers which provider/model pairs rejected a reasoning parameter, so the wasted round trip is
 * paid once rather than on every correction.
 *
 * Process-scoped on purpose: model capabilities change under the same name, and a stale "this
 * doesn't work" persisted to disk would be far more annoying than one extra request after a restart.
 */
object ReasoningSupport {

    private val rejected: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun key(providerId: String, model: String): String = "$providerId::$model"

    fun accepts(key: String): Boolean = key !in rejected

    fun markRejected(key: String) {
        rejected.add(key)
    }

    /** Test seam — nothing in the app clears this. */
    internal fun reset() = rejected.clear()
}

/**
 * Runs [send] with the reasoning parameter, and once without it if the provider rejects the request.
 *
 * The retry triggers on the status code rather than on error text: every provider words this
 * differently, and a message match that misses simply surfaces a confusing 400 to the user. A 400
 * caused by something else fails identically on the retry, so the fallback costs a round trip in
 * that case and never changes the outcome.
 */
internal suspend fun withReasoningFallback(
    cacheKey: String,
    wanted: Boolean,
    send: suspend (includeReasoning: Boolean) -> String,
): String {
    val include = wanted && ReasoningSupport.accepts(cacheKey)
    if (!include) return send(false)

    return try {
        send(true)
    } catch (e: AiException) {
        if (e.status == 400 || e.status == 422) {
            ReasoningSupport.markRejected(cacheKey)
            send(false)
        } else {
            throw e
        }
    }
}
