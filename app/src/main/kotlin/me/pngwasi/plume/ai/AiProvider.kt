package me.pngwasi.plume.ai

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
) : Exception(message, cause) {
    enum class Kind { NotConfigured, Auth, RateLimit, Server, Network, Timeout, BadResponse, Empty }
}

object AiHttp {
    /**
     * Shared across the three entry activities. Connection pooling is what makes a second
     * invocation noticeably faster than the first.
     */
    @Volatile
    private var client: OkHttpClient? = null

    fun client(timeoutSeconds: Int): OkHttpClient {
        val base = client ?: synchronized(this) {
            client ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
                .also { client = it }
        }
        return base.newBuilder()
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }
}
