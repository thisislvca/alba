package me.lvca.alba.protocol.photos

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.auth.AppleProtocolError
import me.lvca.alba.protocol.auth.AppleProtocolException
import me.lvca.alba.protocol.network.AppleClientProfile.addWebClientBuild
import me.lvca.alba.protocol.network.AppleEndpointPolicy
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import me.lvca.alba.protocol.network.AppleSessionHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What every iCloud Photos web-service caller shares: binding the shared transport to the signed-in
 * session, building CloudKit URLs, sending requests, reporting HTTP failures and saving the session
 * state each response carries.
 */
internal class CloudKitClient(
    private val transport: AppleHttpTransport,
    private val currentSession: () -> AppleSessionSnapshot?,
    private val onSessionUpdated: suspend (AppleSessionSnapshot) -> Unit,
    private val onSessionRejected: suspend (AppleSessionSnapshot) -> Unit,
) {
    private val accountChangeListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var boundAuthentication: String? = null

    /** [listener] runs whenever [bind] moves the transport to another account or client session. */
    fun onAccountChanged(listener: () -> Unit) {
        accountChangeListeners += listener
    }

    /** Restores the transport's cookies when [session] differs from the session bound last. */
    @Synchronized
    fun bind(session: AppleSessionSnapshot) {
        val authentication = "${session.dsid}:${session.clientId}"
        if (boundAuthentication == authentication) return
        transport.restore(session)
        accountChangeListeners.forEach { it() }
        boundAuthentication = authentication
    }

    /** The Photos container URL for [path] in the private, shared or public [database], without a query. */
    fun databaseUrl(session: AppleSessionSnapshot, database: String, path: String): String {
        require(database in DATABASES) { "Unknown CloudKit database" }
        val root = session.webservices[CLOUDKIT_SERVICE]
            ?: throw AppleProtocolException(AppleProtocolError.PHOTOS_UNAVAILABLE, "iCloud Photos is unavailable for this account.")
        require(AppleEndpointPolicy.requireAllowed(root).host.contains(CLOUDKIT_SERVICE, ignoreCase = true)) {
            "Apple returned an unexpected Photos database host"
        }
        return "${root.trimEnd('/')}/database/1/com.apple.photos.cloud/production/$database/${path.trimStart('/')}"
    }

    /**
     * [url] with the session query iCloud.com sends. Shared-album requests have always omitted the
     * client build, and the library asks CloudKit for the current sync token on every request.
     */
    fun withSessionQuery(
        url: String,
        session: AppleSessionSnapshot,
        clientBuild: Boolean = true,
        currentSyncToken: Boolean = false,
    ): String = url.toHttpUrl().newBuilder()
        .apply { if (clientBuild) addWebClientBuild() }
        .addQueryParameter("clientId", session.clientId)
        .addQueryParameter("dsid", session.dsid)
        .addQueryParameter("remapEnums", "true")
        .apply { if (currentSyncToken) addQueryParameter("getCurrentSyncToken", "true") }
        .build()
        .toString()

    /**
     * Sends [request] for [session] and saves the session state it returned. [verify] runs first so
     * a caller can refuse a response that arrived after the account changed.
     */
    suspend fun exchange(
        session: AppleSessionSnapshot,
        request: AppleHttpRequest,
        verify: (AppleSessionSnapshot) -> Unit = {},
    ): AppleHttpResponse {
        val response = transport.execute(request)
        verify(session)
        persist(session, response)
        return response
    }

    /** [response] when it succeeded; otherwise expires [session] if [failures] says so and throws. */
    suspend fun requireSuccess(
        session: AppleSessionSnapshot,
        response: AppleHttpResponse,
        failures: CloudKitFailures = CloudKitFailures.LIBRARY,
    ): AppleHttpResponse {
        if (response.code in 200..299) return response
        if (failures.rejectsSession(response.code)) onSessionRejected(session)
        throw failures.error(response.code)
    }

    suspend fun send(
        session: AppleSessionSnapshot,
        request: AppleHttpRequest,
        failures: CloudKitFailures = CloudKitFailures.LIBRARY,
        verify: (AppleSessionSnapshot) -> Unit = {},
    ): AppleHttpResponse = requireSuccess(session, exchange(session, request, verify), failures)

    /**
     * Saves the transport's cookies and the session headers of [response], if any. They are applied to the
     * latest saved session for the same sign-in rather than to [session], which this call read when it
     * started: an overlapping call may have saved a newer session token since.
     */
    suspend fun persist(session: AppleSessionSnapshot, response: AppleHttpResponse?) {
        val headers = response?.sessionHeaders ?: AppleSessionHeaders()
        val latest = currentSession()?.takeIf { it.dsid == session.dsid && it.clientId == session.clientId } ?: session
        onSessionUpdated(headers.applyTo(latest).copy(cookies = transport.snapshotCookies()))
    }

    /** Marks [session] expired after a failure seen outside [requireSuccess], such as an upload. */
    suspend fun reject(session: AppleSessionSnapshot) = onSessionRejected(session)

    private companion object {
        const val CLOUDKIT_SERVICE = "ckdatabasews"
        val DATABASES = setOf("private", "shared", "public")
    }
}

/** How a Photos web service reports a non-2xx HTTP status. */
internal enum class CloudKitFailures {
    /** The personal library and uploads: 401 and 403 both mean the web session is no longer valid. */
    LIBRARY,

    /**
     * Shared albums: only 401 expires the session. Shared-album endpoints also answer 403 when this
     * account lost access to one album, for example after its owner removed the person or stopped
     * sharing, and that must not sign the person out of their whole library. The shared-album tests
     * pin this.
     */
    SHARED_ALBUMS,
    ;

    fun rejectsSession(code: Int): Boolean = code == 401 || (this == LIBRARY && code == 403)

    fun error(code: Int): AppleProtocolException = when (this) {
        LIBRARY -> AppleProtocolException(
            if (rejectsSession(code)) AppleProtocolError.SESSION_EXPIRED else AppleProtocolError.NETWORK,
            when {
                rejectsSession(code) -> "The iCloud session expired. Sign in again."
                code == 429 -> "Apple is limiting Photos requests. Wait a moment and refresh."
                else -> "Apple Photos returned HTTP $code."
            },
        )
        SHARED_ALBUMS -> AppleProtocolException(
            if (rejectsSession(code)) AppleProtocolError.SESSION_EXPIRED else AppleProtocolError.PHOTOS_UNAVAILABLE,
            "Shared albums returned HTTP $code. Refresh to check access.",
        )
    }
}
