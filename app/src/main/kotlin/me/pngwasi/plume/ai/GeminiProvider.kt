package me.pngwasi.plume.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST {baseUrl}/v1beta/models/{model}:generateContent` — Google's Generative Language API.
 * The system prompt goes in `systemInstruction`; the reply arrives as candidate parts.
 */
class GeminiProvider(
    override val id: String,
    private val label: String,
    private val apiKey: String,
    private val baseUrl: String,
    override val model: String,
    private val temperature: Float,
    private val timeoutSeconds: Int,
) : AiProvider {

    override suspend fun complete(systemPrompt: String, userText: String): String {
        val endpoint = "${baseUrl.trimEnd('/')}/v1beta/models/$model:generateContent"

        val payload = buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject { put("parts", buildJsonArray { add(textPart(systemPrompt)) }) },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("parts", buildJsonArray { add(textPart(userText)) })
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject { put("temperature", JsonPrimitive(temperature)) },
            )
        }

        // The key travels as a header rather than a query parameter so it stays out of access logs.
        val body = postJson(endpoint, payload.toString(), timeoutSeconds, label) {
            it.header("x-goog-api-key", apiKey)
        }
        return parse(body, label)
    }

    private fun textPart(text: String) = buildJsonObject { put("text", JsonPrimitive(text)) }

    companion object {
        internal fun parse(body: String, label: String): String {
            val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw AiException(AiException.Kind.BadResponse, "$label returned a malformed response.")

            val candidates = runCatching { root["candidates"]?.jsonArray }.getOrNull()
            if (candidates.isNullOrEmpty()) {
                // A safety block returns 200 with promptFeedback and no candidates.
                val blocked = runCatching {
                    root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw AiException(
                    AiException.Kind.Empty,
                    blocked?.let { "$label blocked this text ($it)." }
                        ?: extractMessage(body)
                        ?: "$label returned no candidates.",
                )
            }

            val text = runCatching {
                candidates.first().jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("")
            }.getOrNull()

            return text?.takeIf { it.isNotBlank() }
                ?: throw AiException(AiException.Kind.Empty, "$label returned an empty response.")
        }
    }
}
