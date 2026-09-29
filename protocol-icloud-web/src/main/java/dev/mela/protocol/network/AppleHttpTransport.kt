package dev.mela.protocol.network

import dev.mela.protocol.account.AppleSessionSnapshot
import dev.mela.protocol.account.PersistedCookie
import java.io.OutputStream
import java.io.InputStream

data class AppleSessionHeaders(
    val accountCountryCode: String? = null,
    val sessionId: String? = null,
    val sessionToken: String? = null,
    val trustToken: String? = null,
    val scnt: String? = null,
    val authAttributes: String? = null,
)

data class AppleHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val oneShot: Boolean = false,
)

data class AppleHttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

interface AppleHttpTransport {
    suspend fun openRange(url: String, position: Long, length: Long): dev.mela.engine.source.MediaRead = error("Range reads unavailable")
    val sessionHeaders: AppleSessionHeaders

    suspend fun execute(request: AppleHttpRequest): AppleHttpResponse

    suspend fun stream(url: String, output: OutputStream)

    suspend fun executeRawUpload(
        url: String,
        headers: Map<String, String>,
        contentLength: Long,
        openBodyOnce: () -> InputStream,
    ): AppleHttpResponse = error("Raw upload is not supported by this transport")

    fun snapshotCookies(): List<PersistedCookie>

    fun restore(snapshot: AppleSessionSnapshot)

    fun clear()
}

class AppleMediaHttpException(val status: Int) : java.io.IOException("Apple media returned HTTP $status")
