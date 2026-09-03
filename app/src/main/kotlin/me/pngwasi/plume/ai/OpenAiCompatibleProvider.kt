package me.pngwasi.plume.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pngwasi.plume.data.ReasoningDialect
import me.pngwasi.plume.data.ReasoningMode

/**
 * `POST {baseUrl}/chat/completions` — OpenAI's shape, and the one nearly every third-party gateway
 * speaks (OpenRouter, Groq, Together, Mistral, DeepSeek, Ollama, LM Studio, vLLM). Custom providers
 * use this client, which is why "OpenAI-compatible" is the default kind for them.
 */
class OpenAiCompatibleProvider(
    override val id: String,
    private val label: String,
    private val apiKey: String,
    private val baseUrl: String,
    override val model: String,
    private val temperature: Float,
    private val timeoutSeconds: Int,
    private val reasoning: ReasoningMode = ReasoningMode.ProviderDefault,
    private val dialect: ReasoningDialect = ReasoningDialect.Auto,
) : AiProvider {

    private val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"
    private val style = Reasoning.styleFor(
        me.pngwasi.plume.data.ProviderKind.OpenAiCompatible,
        baseUrl,
        dialect,
    )

    override suspend fun complete(systemPrompt: String, userText: String): String {
        val cacheKey = ReasoningSupport.key(id, model)

        val body = withReasoningFallback(cacheKey, reasoning == ReasoningMode.Low) { includeReasoning ->
            postJson(endpoint, payload(systemPrompt, userText, includeReasoning), timeoutSeconds, label) {
                // A blank key means a local runtime that wants no auth at all; an empty Bearer
                // header is worse than none, and some servers reject it outright.
                if (apiKey.isBlank()) it else it.header("Authorization", "Bearer $apiKey")
            }
        }
        return parse(body, label)
    }

    private fun payload(systemPrompt: String, userText: String, includeReasoning: Boolean): String =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            put("temperature", JsonPrimitive(temperature))
            put(
                "messages",
                buildJsonArray {
                    add(message("system", systemPrompt))
                    add(message("user", userText))
                },
            )
            if (includeReasoning) {
                Reasoning.chatFields(style, reasoning).forEach { (key, value) -> put(key, value) }
            }
        }.toString()

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(role))
        put("content", JsonPrimitive(content))
    }

    companion object {
        internal fun parse(body: String, label: String): String {
            val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw AiException(AiException.Kind.BadResponse, "$label returned a malformed response.")

            val choices = runCatching { root["choices"]?.jsonArray }.getOrNull()
            if (choices.isNullOrEmpty()) {
                throw AiException(
                    AiException.Kind.Empty,
                    extractMessage(body) ?: "$label returned no completion.",
                )
            }

            val content = runCatching {
                choices.first().jsonObject["message"]?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull()

            return content
                ?: throw AiException(AiException.Kind.Empty, "$label returned an empty completion.")
        }
    }
}
