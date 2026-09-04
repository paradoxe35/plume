package me.pngwasi.plume.ai

/**
 * One text-in / text-out call. Every provider maps a system prompt plus the user's selection onto
 * its own wire format and returns the model's plain-text reply.
 */
interface AiProvider {
    val id: String
    val model: String

    /** @throws AiException for every failure, so callers have one error type to render. */
    suspend fun complete(systemPrompt: String, userText: String): String
}

/** Failure with a message already phrased for the user — these surface directly in the UI. */
class AiException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
    /** HTTP status when the failure came from a response, so callers can react to 400 vs 500. */
    val status: Int? = null,
) : Exception(message, cause) {
    enum class Kind { NotConfigured, Auth, RateLimit, Server, Network, Timeout, BadResponse, Empty }
}

