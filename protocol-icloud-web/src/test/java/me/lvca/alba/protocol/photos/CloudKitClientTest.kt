package me.lvca.alba.protocol.photos

import kotlinx.coroutines.runBlocking
import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.network.AppleHttpRequest
import me.lvca.alba.protocol.network.AppleHttpResponse
import me.lvca.alba.protocol.network.AppleHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.OutputStream

class CloudKitClientTest {
    @Test fun persistingAnOlderCallKeepsTheSessionTokenAnOverlappingCallSaved() = runBlocking {
        val started = SESSION.copy(sessionToken = "token-1")
        var saved: AppleSessionSnapshot = started
        val client = CloudKitClient(NoTransport, { saved }, { saved = it }, {})

        // Call A rotates the token and saves it while call B, which started from the same session, is still running.
        saved = started.copy(sessionToken = "token-2")
        client.persist(started, AppleHttpResponse(200, emptyMap(), "{}"))

        assertEquals("token-2", saved.sessionToken)
    }

    @Test fun persistingAfterAnAccountChangeDoesNotMixSessions() = runBlocking {
        val started = SESSION.copy(sessionToken = "token-1")
        var saved: AppleSessionSnapshot = SESSION.copy(dsid = "other", sessionToken = "other-token")
        val client = CloudKitClient(NoTransport, { saved }, { saved = it }, {})

        client.persist(started, null)

        assertEquals("123456789", saved.dsid)
        assertEquals("token-1", saved.sessionToken)
    }

    private object NoTransport : AppleHttpTransport {
        override suspend fun execute(request: AppleHttpRequest) = error("Not used")
        override suspend fun stream(url: String, output: OutputStream) = error("Not used")
        override fun snapshotCookies() = SESSION.cookies
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
    }

    private companion object {
        val SESSION = AppleSessionSnapshot(
            accountName = "person@example.com",
            clientId = "client-id",
            accountCountryCode = "IT",
            sessionId = "session-id",
            sessionToken = "session-token",
            trustToken = "trust-token",
            dsid = "123456789",
            webservices = mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"),
            cookies = emptyList(),
        )
    }
}
