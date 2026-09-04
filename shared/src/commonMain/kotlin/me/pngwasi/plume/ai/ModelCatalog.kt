package me.pngwasi.plume.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ProviderKind

/**
 * Fetches the model list a provider actually offers, so the model field can be a picker instead of
 * a string the user has to get exactly right.
 *
 * Every provider here exposes a list endpoint, but none of them is guaranteed — self-hosted
 * gateways and proxies often omit it. Callers treat failure as "fall back to free text", never as
 * an error worth blocking on.
 */
object ModelCatalog {

    suspend fun list(
        config: ProviderConfig,
        apiKey: String,
        timeoutSeconds: Int = 20,
        http: HttpClient = AiHttp.shared,
    ): List<String> {
        val base = config.baseUrl.trimEnd('/')
        val label = config.label.ifBlank { "Provider" }

        return when (config.kind) {
            ProviderKind.OpenAiCompatible -> {
                // OpenRouter and local runtimes serve their catalogue unauthenticated; OpenAI does
                // not.
                val body = http.getJson("$base/models", timeoutSeconds, label) { bearer(apiKey) }
                parseOpenAi(body)
            }

            ProviderKind.Gemini -> {
                val body = http.getJson("$base/v1beta/models", timeoutSeconds, label) {
                    if (apiKey.isNotBlank()) header("x-goog-api-key", apiKey)
                }
                parseGemini(body)
            }
        }
    }

    /** `{"data":[{"id":"gpt-4o"}, ...]}` */
    internal fun parseOpenAi(body: String): List<String> {
        val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val data = runCatching { root["data"]?.jsonArray }.getOrNull() ?: return emptyList()
        return data.mapNotNull {
            runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }.filter { it.isNotBlank() }.sorted()
    }

    /**
     * `{"models":[{"name":"models/gemini-2.5-flash","supportedGenerationMethods":[...]}]}`
     *
     * Embedding and other non-chat models share the endpoint, so anything that cannot
     * `generateContent` is filtered out rather than offered and later rejected at call time.
     */
    internal fun parseGemini(body: String): List<String> {
        val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val models = runCatching { root["models"]?.jsonArray }.getOrNull() ?: return emptyList()

        return models.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val methods = obj["supportedGenerationMethods"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (methods != null && "generateContent" !in methods) return@runCatching null
                obj["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
            }.getOrNull()
        }.filter { it.isNotBlank() }.sorted()
    }
}
