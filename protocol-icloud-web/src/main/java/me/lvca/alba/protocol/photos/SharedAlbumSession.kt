package me.lvca.alba.protocol.photos

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Serializes shared-album operations and validates their session before and after network I/O. */
internal class SharedAlbumSession(
    private val transport: AppleHttpTransport,
    private val session: () -> AppleSessionSnapshot,
    private val prepare: (AppleSessionSnapshot) -> Unit,
    private val persist: suspend (AppleSessionSnapshot) -> Unit,
    private val authorized: () -> Boolean,
    private val reject: suspend (AppleSessionSnapshot) -> Unit,
    private val onAccountChanged: () -> Unit,
) {
    private val lock = Mutex()
    private var binding: String? = null
    private var operationSession: AppleSessionSnapshot? = null
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock {
        operationSession = bound()
        try { block() } finally { operationSession = null }
    }

    fun bound(): AppleSessionSnapshot {
        val current = session()
        prepare(current)
        val key = "${current.dsid}:${current.clientId}"
        if (binding != key) { onAccountChanged(); binding = key }
        return current
    }
    fun checkOperation() { operationSession?.let(::checkSession) }

    fun checkSession(expected: AppleSessionSnapshot) {
        check(session().let { it.dsid == expected.dsid && it.clientId == expected.clientId }) { "Account changed. Refresh shared albums." }
    }
    suspend fun request(url: String, body: JsonObject, write: Boolean = false): JsonObject =
        requestElement(url, body, write) as? JsonObject ?: error("Apple returned an invalid shared album response.")
    suspend fun requestElement(url: String, body: JsonObject, write: Boolean = false): JsonElement {
        operationSession?.let(::checkSession)
        val current = bound()
        if (write) check(authorized()) { "Sign in before changing shared albums." }
        AppleEndpointPolicy.requireAllowed(url)
        val target = url.toHttpUrl().newBuilder().addQueryParameter("clientId", current.clientId)
            .addQueryParameter("dsid", current.dsid).addQueryParameter("remapEnums", "true").build().toString()
        val response = transport.execute(AppleHttpRequest("POST", target, mapOf("Content-Type" to "text/plain;charset=UTF-8"), body.toString(), oneShot = write))
        checkSession(current)
        persist(current)
        if (response.code !in 200..299) {
            if (response.code == 401) reject(current)
            throw AppleProtocolException(
                if (response.code == 401) AppleProtocolError.SESSION_EXPIRED else AppleProtocolError.PHOTOS_UNAVAILABLE,
                "Shared albums returned HTTP ${response.code}. Refresh to check access.",
            )
        }
        if (write && response.body.isBlank()) return buildJsonObject {}
        val element = Json.parseToJsonElement(response.body)
        val root = element as? JsonObject ?: return element
        check(root["errors"]?.jsonArray.isNullOrEmpty() && root["serverErrorCode"] == null) { "Apple could not load shared albums." }
        if (write) {
            for (key in listOf("records", "zones")) check((root[key] as? JsonArray).orEmpty().none { (it as? JsonObject)?.get("serverErrorCode") != null }) {
                "Apple could not confirm the shared album change. Refresh before retrying."
            }
        }
        return root
    }
    suspend fun ck(scope: String, path: String, body: JsonObject, write: Boolean = false): JsonObject {
        require(scope in setOf("private", "shared", "public"))
        val root = requireNotNull(bound().webservices["ckdatabasews"]) { "Shared albums are unavailable for this account." }
        return request("${root.trimEnd('/')}/database/1/com.apple.photos.cloud/production/$scope/$path", body, write)
    }
}
