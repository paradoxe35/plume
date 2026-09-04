package me.pngwasi.plume.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST {baseUrl}/v1/messages` — Anthropic's Messages API.
 *
 * Not OpenAI-compatible in three ways that each break a request on their own: the key travels in
 * `x-api-key` rather than a bearer token, `anthropic-version` is required, and the system prompt is
 * a top-level field rather than a message with `role: system`. The reply is a list of content
 * blocks rather than one string.
 */
class AnthropicProvider(
    override val id: String,
    private val label: String,
    private val apiKey: String,
    private val baseUrl: String,
    override val model: String,
    private val temperature: Float,
    private val timeoutSeconds: Int,
    private val http: HttpClient = AiHttp.shared,
) : AiProvider {

    override suspend fun complete(systemPrompt: String, userText: String): String {
        val body = http.postJson(
            "${baseUrl.trimEnd('/')}/v1/messages",
            payload(systemPrompt, userText),
            timeoutSeconds,
            label,
        ) {
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
        }
        return parse(body, label)
    }

    private fun payload(systemPrompt: String, userText: String): String =
        buildJsonObject {
            put("model", JsonPrimitive(model))
            // Required, unlike OpenAI's, where omitting it means "as much as the reply needs".
            put("max_tokens", JsonPrimitive(MAX_TOKENS))
            put("temperature", JsonPrimitive(temperature))
            put("system", JsonPrimitive(systemPrompt))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(userText))
                        },
                    )
                },
            )
        }.toString()

    companion object {
        /** Pinned: the header is mandatory and an unrecognised value is rejected outright. */
        private const val API_VERSION = "2023-06-01"

        /**
         * A correction is about as long as its input, which the character limit already bounds.
         * This is a ceiling, not a target — Anthropic stops when the reply is done.
         */
        private const val MAX_TOKENS = 8192

        internal fun parse(body: String, label: String): String {
            val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw AiException(
                    AiException.Kind.BadResponse,
                    "$label returned a malformed response.",
                )

            // Only the text blocks carry the reply. A refusal or a tool block would otherwise be
            // joined in as an empty string and read as success.
            val text = runCatching {
                root["content"]?.jsonArray
                    ?.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("")
            }.getOrNull()

            return text?.takeIf { it.isNotBlank() }
                ?: throw AiException(
                    AiException.Kind.Empty,
                    extractMessage(body) ?: "$label returned an empty response.",
                )
        }
    }
}
