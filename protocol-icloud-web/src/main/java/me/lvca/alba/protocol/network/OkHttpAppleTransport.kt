package me.lvca.alba.protocol.network

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.PersistedCookie
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Authenticator
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.source

class OkHttpAppleTransport(
    private val cookieJar: PersistingCookieJar = PersistingCookieJar(),
    clientBuilder: OkHttpClient.Builder = OkHttpClient.Builder(),
) : AppleHttpTransport {
    override suspend fun openRange(url: String, position: Long, length: Long): me.lvca.alba.engine.source.MediaRead {
        require(position >= 0 && (length == -1L || length > 0))
        val range = "bytes=$position-" + if (length > 0) Math.addExact(position, length - 1) else ""
        // The caller reads and closes the open response; it is released here only on failure or cancellation.
        return downloadFollowingRedirects(url, mapOf("Range" to range), discard = { it.close() }) { response ->
            try {
                if (!response.isSuccessful) throw AppleMediaHttpException(response.code)
                if (position > 0 && response.code != 206) throw IOException("Apple did not honor the requested byte range")
                if (response.code == 206 && response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() != position) {
                    throw IOException("Apple returned a mismatched byte range")
                }
                object : me.lvca.alba.engine.source.MediaRead {
                    override val input = response.body.byteStream()
                    override val length = response.body.contentLength()
                    override fun close() = response.close()
                }
            } catch (error: Throwable) {
                response.close()
                throw error
            }
        }
    }
    private val client = clientBuilder
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val writeClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .build()

    override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
        val url = AppleEndpointPolicy.requireAllowed(request.url)
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", AppleClientProfile.USER_AGENT)
            .header("Origin", "https://www.icloud.com")
            .header("Referer", "https://www.icloud.com/")
        request.headers.forEach(builder::header)
        val delegateBody = request.body?.toRequestBody(
            request.headers["Content-Type"]?.toMediaType() ?: JSON_MEDIA_TYPE,
        )
        val body = if (request.oneShot && delegateBody != null) object : RequestBody() {
            override fun contentType() = delegateBody.contentType()
            override fun contentLength() = delegateBody.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = delegateBody.writeTo(sink)
        } else delegateBody
        when (request.method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: EMPTY_BODY)
            "PUT" -> builder.put(body ?: EMPTY_BODY)
            "DELETE" -> builder.delete(body)
            else -> error("Unsupported Apple request method: ${request.method}")
        }

        return (if (request.oneShot) writeClient else client).newCall(builder.build()).awaitMapped { response ->
            AppleHttpResponse(
                code = response.code,
                headers = response.headers.names().associate { name ->
                    name.lowercase() to response.header(name).orEmpty()
                },
                body = response.body.readUtf8Bounded(MAX_JSON_RESPONSE_BYTES),
            )
        }
    }

    override suspend fun stream(url: String, output: OutputStream) {
        downloadFollowingRedirects(url) { response ->
            response.use {
                if (!response.isSuccessful) throw AppleMediaHttpException(response.code)
                response.body.byteStream().use { input -> input.copyTo(output, DEFAULT_STREAM_BUFFER_BYTES) }
            }
        }
    }

    /**
     * GETs media at [url], following at most [MAX_DOWNLOAD_REDIRECTS] redirects and checking each hop
     * against the endpoint allowlist. [read] runs in the response callback and owns the final response;
     * [discard] releases its result if the caller was cancelled before receiving it.
     */
    private suspend fun <T> downloadFollowingRedirects(
        url: String,
        headers: Map<String, String> = emptyMap(),
        discard: (T) -> Unit = {},
        read: (Response) -> T,
    ): T {
        var target = AppleEndpointPolicy.requireAllowed(url)
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) {
            val request = Request.Builder()
                .url(target)
                .apply { headers.forEach(::header) }
                .header("User-Agent", AppleClientProfile.USER_AGENT)
                .get()
                .build()
            when (val hop = client.newCall(request).awaitHop(read, discard)) {
                is DownloadHop.Done -> return hop.value
                is DownloadHop.Redirect -> {
                    val next = requireNotNull(hop.location?.let(target::resolve)) {
                        "Apple media download returned an invalid redirect"
                    }
                    target = AppleEndpointPolicy.requireAllowed(next.toString())
                }
            }
        }
        throw IOException("Too many media redirects")
    }

    private suspend fun <T> Call.awaitHop(read: (Response) -> T, discard: (T) -> Unit): DownloadHop<T> =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val hop = runCatching {
                            if (response.code in REDIRECT_CODES) {
                                response.use { DownloadHop.Redirect(it.header("Location")) }
                            } else {
                                DownloadHop.Done(read(response))
                            }
                        }
                        hop.fold(
                            onSuccess = { value ->
                                continuation.resume(value) { _, unused, _ ->
                                    (unused as? DownloadHop.Done)?.let { discard(it.value) }
                                }
                            },
                            onFailure = { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            },
                        )
                    }
                },
            )
        }

    override suspend fun executeRawUpload(
        url: String,
        headers: Map<String, String>,
        contentLength: Long,
        openBodyOnce: () -> InputStream,
    ): AppleHttpResponse {
        val allowedUrl = AppleEndpointPolicy.requireAllowed(url)
        val body = object : RequestBody() {
            override fun contentType() = UPLOAD_MEDIA_TYPE

            override fun contentLength(): Long = contentLength

            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: okio.BufferedSink) {
                openBodyOnce().use { input -> sink.writeAll(input.source()) }
            }
        }
        val request = Request.Builder()
            .url(allowedUrl)
            .header("User-Agent", AppleClientProfile.USER_AGENT)
            .header("Origin", "https://www.icloud.com")
            .header("Referer", "https://www.icloud.com/")
            .apply { headers.forEach(::header) }
            .post(body)
            .build()
        return writeClient.newCall(request).awaitMapped { response ->
            AppleHttpResponse(
                code = response.code,
                headers = response.headers.names().associate { name ->
                    name.lowercase() to response.header(name).orEmpty()
                },
                body = response.body.readUtf8Bounded(MAX_UPLOAD_RESPONSE_BYTES),
            )
        }
    }

    override fun snapshotCookies(): List<PersistedCookie> = cookieJar.snapshot()

    override fun restore(snapshot: AppleSessionSnapshot) {
        cookieJar.restore(snapshot.cookies)
    }

    override fun clear() {
        cookieJar.clear()
    }

    private suspend fun <T> Call.awaitMapped(transform: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use(transform) }
                        result.fold(
                            onSuccess = { value ->
                                continuation.resume(value) { _, _, _ -> Unit }
                            },
                            onFailure = { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            },
                        )
                    }
                },
            )
        }

    private sealed interface DownloadHop<out T> {
        data class Redirect(val location: String?) : DownloadHop<Nothing>
        data class Done<T>(val value: T) : DownloadHop<T>
    }

    private fun okhttp3.ResponseBody.readUtf8Bounded(maxBytes: Int): String {
        require(maxBytes > 0)
        val bounded = Buffer()
        while (bounded.size <= maxBytes) {
            val remaining = maxBytes + 1L - bounded.size
            val read = source().read(bounded, minOf(DEFAULT_STREAM_BUFFER_BYTES.toLong(), remaining))
            if (read == -1L) break
        }
        check(bounded.size <= maxBytes) { "Apple response exceeded the safe in-memory limit" }
        return bounded.readString(Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_STREAM_BUFFER_BYTES = 64 * 1_024
        const val MAX_DOWNLOAD_REDIRECTS = 5
        const val MAX_JSON_RESPONSE_BYTES = 8 * 1_024 * 1_024
        const val MAX_UPLOAD_RESPONSE_BYTES = 1 * 1_024 * 1_024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val UPLOAD_MEDIA_TYPE = "text/plain".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
