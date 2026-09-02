package me.pngwasi.plume.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

private val JsonMedia = "application/json; charset=utf-8".toMediaType()

/**
 * One POST, one string back. Every provider funnels through here so timeout, offline and HTTP-error
 * handling stay identical across them.
 */
internal suspend fun postJson(
    url: String,
    json: String,
    timeoutSeconds: Int,
    label: String,
    headers: (Request.Builder) -> Request.Builder = { it },
): String = withContext(Dispatchers.IO) {
    val request = headers(
        Request.Builder()
            .url(url)
            .post(json.toRequestBody(JsonMedia))
            .header("Content-Type", "application/json"),
    ).build()

    val response = try {
        AiHttp.client(timeoutSeconds).newCall(request).execute()
    } catch (e: InterruptedIOException) {
        throw AiException(
            AiException.Kind.Timeout,
            "$label did not answer within ${timeoutSeconds}s. Raise the timeout in Settings or try again.",
            e,
        )
    } catch (e: UnknownHostException) {
        throw AiException(AiException.Kind.Network, "No connection to $label. Check your network.", e)
    } catch (e: IOException) {
        throw AiException(AiException.Kind.Network, "Could not reach $label: ${e.message ?: "network error"}", e)
    }

    response.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw httpError(it.code, body, label)
        body
    }
}

/** GET counterpart, used for provider model catalogues. */
internal suspend fun getJson(
    url: String,
    timeoutSeconds: Int,
    label: String,
    headers: (Request.Builder) -> Request.Builder = { it },
): String = withContext(Dispatchers.IO) {
    val request = headers(Request.Builder().url(url).get()).build()

    val response = try {
        AiHttp.client(timeoutSeconds).newCall(request).execute()
    } catch (e: InterruptedIOException) {
        throw AiException(AiException.Kind.Timeout, "$label did not answer in time.", e)
    } catch (e: UnknownHostException) {
        throw AiException(AiException.Kind.Network, "No connection to $label.", e)
    } catch (e: IOException) {
        throw AiException(AiException.Kind.Network, "Could not reach $label.", e)
    }

    response.use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw httpError(it.code, body, label)
        body
    }
}
