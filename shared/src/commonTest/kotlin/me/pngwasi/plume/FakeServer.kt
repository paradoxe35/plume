package me.pngwasi.plume

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/**
 * A queued HTTP double, replacing MockWebServer.
 *
 * MockWebServer is a JVM library, so the provider tests could only run on the JVM. Ktor's
 * MockEngine swaps the transport rather than the server, keeping the whole client stack — request
 * building, headers, timeouts, status handling — under test on every target.
 *
 * The surface mirrors the MockWebServer one these tests were written against.
 */
class FakeServer(baseUrl: String = "https://plume.test") {

    private val endpoints = mutableMapOf<String, FakeEndpoint>()
    private val default = endpoint(baseUrl)

    val baseUrl: String get() = default.baseUrl

    /** Runs just before a response is served, to model the world changing mid-request. */
    var onRequest: (() -> Unit)? = null

    fun enqueue(response: FakeResponse) = default.enqueue(response)

    fun takeRequest(): RecordedRequest = default.takeRequest()

    val requestCount: Int get() = default.requestCount

    /**
     * A second endpoint on the same client, so per-action provider routing can be tested. One
     * client means one engine, so hosts are what separate them.
     */
    fun endpoint(url: String): FakeEndpoint =
        endpoints.getOrPut(Url(url).host) { FakeEndpoint(url) }

    val client: HttpClient = HttpClient(
        MockEngine { request ->
            onRequest?.invoke()
            val endpoint = endpoints[request.url.host]
                ?: error("No endpoint registered for ${request.url.host}")
            endpoint.record(
                RecordedRequest(
                    method = request.method.value,
                    url = request.url.toString(),
                    body = request.body.asText(),
                    headers = request.headers,
                ),
            )
            val response = endpoint.next()
                ?: error("No response queued for ${request.method.value} ${request.url}")
            respond(
                content = response.body,
                status = HttpStatusCode.fromValue(response.status),
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        },
    ) {
        expectSuccess = false
        install(HttpTimeout)
    }
}

class FakeEndpoint(val baseUrl: String) {

    private val queued = ArrayDeque<FakeResponse>()
    private val recorded = mutableListOf<RecordedRequest>()
    private var taken = 0

    val requestCount: Int get() = recorded.size

    fun enqueue(response: FakeResponse) {
        queued.addLast(response)
    }

    fun takeRequest(): RecordedRequest = recorded[taken++]

    internal fun record(request: RecordedRequest) {
        recorded += request
    }

    internal fun next(): FakeResponse? = queued.removeFirstOrNull()
}

class FakeResponse(val status: Int = 200, val body: String = "") {
    fun setResponseCode(code: Int) = FakeResponse(code, body)
    fun setBody(content: String) = FakeResponse(status, content)
}

class RecordedRequest(
    val method: String,
    val url: String,
    val body: String,
    val headers: Headers,
) {
    fun getHeader(name: String): String? = headers[name]

    /** Path and query, matching what MockWebServer's `path` returned. */
    val path: String get() = Url(url).encodedPathAndQuery
}

private fun OutgoingContent.asText(): String = when (this) {
    is TextContent -> text
    is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
    else -> ""
}
