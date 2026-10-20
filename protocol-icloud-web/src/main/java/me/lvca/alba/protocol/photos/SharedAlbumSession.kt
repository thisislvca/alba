package me.lvca.alba.protocol.photos

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleClientProfile
import me.lvca.alba.protocol.network.AppleHttpRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Serializes shared-album operations and validates their session before and after network I/O. */
internal class SharedAlbumSession(
    private val client: CloudKitClient,
    private val session: () -> AppleSessionSnapshot,
    private val authorized: () -> Boolean,
    onAccountChanged: () -> Unit,
) {
    private val lock = Mutex()
    private var operationSession: AppleSessionSnapshot? = null

    init {
        client.onAccountChanged(onAccountChanged)
    }

    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock {
        operationSession = bound()
        try { block() } finally { operationSession = null }
    }

    fun bound(): AppleSessionSnapshot = session().also(client::bind)

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
        val request = AppleHttpRequest(
            "POST",
            client.withSessionQuery(url, current, clientBuild = false),
            mapOf("Content-Type" to AppleClientProfile.WEB_SERVICE_CONTENT_TYPE),
            body.toString(),
            oneShot = write,
        )
        val response = client.send(current, request, CloudKitFailures.SHARED_ALBUMS, verify = ::checkSession)
        if (write && response.body.isBlank()) return buildJsonObject {}
        val element = Json.parseToJsonElement(response.body)
        val root = element as? JsonObject ?: return element
        check(!CloudKitErrors.rejectedRequest(root)) { "Apple could not load shared albums." }
        if (write) {
            for (key in listOf("records", "zones")) check((root[key] as? JsonArray).orEmpty().none { (it as? JsonObject)?.get("serverErrorCode") != null }) {
                "Apple could not confirm the shared album change. Refresh before retrying."
            }
        }
        return root
    }
    suspend fun ck(scope: String, path: String, body: JsonObject, write: Boolean = false): JsonObject =
        request(client.databaseUrl(bound(), scope, path), body, write)
}
