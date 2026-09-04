package me.pngwasi.plume.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException

/** One engine per platform: OkHttp on Android and the JVM, Darwin on iOS. */
internal expect fun defaultHttpEngine(): HttpClientEngine

object AiHttp {
    /**
     * One client for the whole process. Connection pooling is what makes a second invocation
     * noticeably faster than the first, and Plume is invoked cold constantly.
     */
    val shared: HttpClient by lazy {
        HttpClient(defaultHttpEngine()) {
            expectSuccess = false
            install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS }
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 15_000L
}

/**
 * One request, one string back. Every provider funnels through here so timeout, offline and
 * HTTP-error handling stay identical across them.
 */
internal suspend fun HttpClient.postJson(
    url: String,
    json: String,
    timeoutSeconds: Int,
    label: String,
    headers: HttpRequestBuilder.() -> Unit = {},
): String = receive(label) {
    post(url) {
        contentType(ContentType.Application.Json)
        applyTimeout(timeoutSeconds)
        headers()
        setBody(json)
    }
}

/** GET counterpart, used for provider model catalogues. */
internal suspend fun HttpClient.getJson(
    url: String,
    timeoutSeconds: Int,
    label: String,
    headers: HttpRequestBuilder.() -> Unit = {},
): String = receive(label) {
    get(url) {
        applyTimeout(timeoutSeconds)
        headers()
    }
}

/**
 * Sets a bearer header only when there is a key. A blank one means a local runtime that wants no
 * auth, and an empty `Authorization` header is worse than none — some servers reject it outright.
 */
internal fun HttpRequestBuilder.bearer(apiKey: String) {
    if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
}

private fun HttpRequestBuilder.applyTimeout(timeoutSeconds: Int) {
    val millis = timeoutSeconds.toLong() * 1000
    timeout {
        requestTimeoutMillis = millis
        socketTimeoutMillis = millis
    }
}

private suspend fun receive(label: String, call: suspend () -> HttpResponse): String {
    val response = try {
        call()
    } catch (e: HttpRequestTimeoutException) {
        throw AiException(
            AiException.Kind.Timeout,
            "$label did not answer in time. Raise the timeout in Settings or try again.",
            e,
        )
    } catch (e: ConnectTimeoutException) {
        throw AiException(AiException.Kind.Timeout, "Could not reach $label in time.", e)
    } catch (e: SocketTimeoutException) {
        throw AiException(AiException.Kind.Timeout, "$label stopped responding mid-request.", e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw AiException(
            AiException.Kind.Network,
            "Could not reach $label: ${e.message ?: "network error"}. Check your connection.",
            e,
        )
    }

    val body = response.bodyAsText()
    val status = response.status.value
    if (status !in 200..299) throw httpError(status, body, label)
    return body
}
