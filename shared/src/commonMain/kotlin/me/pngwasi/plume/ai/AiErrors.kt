package me.pngwasi.plume.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Turns an HTTP failure into something a user can act on. Providers phrase errors differently, so
 * the status code drives the message and the body is only used to add detail.
 */
internal fun httpError(status: Int, body: String, providerLabel: String): AiException {
    val detail = extractMessage(body)
    return when (status) {
        401, 403 -> AiException(
            AiException.Kind.Auth,
            // No "check it in Settings": this same text is shown inside Settings, under the
            // key field, where that reads as nonsense. Callers that have nowhere to click add
            // their own hint.
            "$providerLabel rejected the API key.",
            status = status,
        )
        404 -> AiException(
            AiException.Kind.BadResponse,
            detail ?: "$providerLabel has no such model or endpoint. Check the model name and base URL.",
            status = status,
        )
        429 -> AiException(
            AiException.Kind.RateLimit,
            "$providerLabel is rate limiting you. Wait a moment and try again.",
            status = status,
        )
        in 500..599 -> AiException(
            AiException.Kind.Server,
            "$providerLabel is having trouble (HTTP $status). Try again shortly.",
            status = status,
        )
        else -> AiException(
            AiException.Kind.BadResponse,
            detail ?: "$providerLabel returned HTTP $status.",
            status = status,
        )
    }
}

/**
 * Pulls a human message out of the common error envelopes:
 * `{"error":{"message":..}}`, `{"error":"..."}`, `{"message":..}`.
 */
internal fun extractMessage(body: String): String? {
    if (body.isBlank()) return null
    return runCatching {
        val root = LenientJson.parseToJsonElement(body).jsonObject
        val error = root["error"]
        val text = when {
            error == null -> root["message"]?.jsonPrimitive?.content
            error is kotlinx.serialization.json.JsonPrimitive -> error.content
            else -> error.jsonObject["message"]?.jsonPrimitive?.content
        }
        text?.takeIf { it.isNotBlank() }?.take(300)
    }.getOrNull()
}
