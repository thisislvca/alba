package me.lvca.alba.protocol.network

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.PersistedCookie
import java.io.InputStream
import java.io.OutputStream

data class AppleSessionHeaders(
    val accountCountryCode: String? = null,
    val sessionId: String? = null,
    val sessionToken: String? = null,
    val trustToken: String? = null,
    val scnt: String? = null,
    val authAttributes: String? = null,
) {
    /** These headers updated by a later response; a header the response omitted keeps its value. */
    fun updatedBy(newer: AppleSessionHeaders) = AppleSessionHeaders(
        accountCountryCode = newer.accountCountryCode ?: accountCountryCode,
        sessionId = newer.sessionId ?: sessionId,
        sessionToken = newer.sessionToken ?: sessionToken,
        trustToken = newer.trustToken ?: trustToken,
        scnt = newer.scnt ?: scnt,
        authAttributes = newer.authAttributes ?: authAttributes,
    )

    /** The snapshot with the session values from these headers. */
    fun applyTo(session: AppleSessionSnapshot): AppleSessionSnapshot = session.copy(
        accountCountryCode = accountCountryCode ?: session.accountCountryCode,
        sessionId = sessionId ?: session.sessionId,
        sessionToken = sessionToken ?: session.sessionToken,
        trustToken = trustToken ?: session.trustToken,
    )

    companion object {
        fun of(snapshot: AppleSessionSnapshot) = AppleSessionHeaders(
            accountCountryCode = snapshot.accountCountryCode,
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            trustToken = snapshot.trustToken,
        )
    }
}

data class AppleHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val oneShot: Boolean = false,
)

/** [headers] are keyed by lower-case name. */
data class AppleHttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** The Apple session headers this response set. Each caller reads its own responses. */
    val sessionHeaders: AppleSessionHeaders
        get() = AppleSessionHeaders(
            accountCountryCode = header("x-apple-id-account-country"),
            sessionId = header("x-apple-id-session-id"),
            sessionToken = header("x-apple-session-token"),
            trustToken = header("x-apple-twosv-trust-token"),
            scnt = header("scnt"),
            authAttributes = header("x-apple-auth-attributes"),
        )
}

interface AppleHttpTransport {
    suspend fun openRange(url: String, position: Long, length: Long): me.lvca.alba.engine.source.MediaRead = error("Range reads unavailable")

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
