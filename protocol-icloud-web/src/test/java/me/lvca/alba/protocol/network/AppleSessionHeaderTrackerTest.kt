package me.lvca.alba.protocol.network

import me.lvca.alba.protocol.account.AppleSessionSnapshot
import me.lvca.alba.protocol.account.PersistedCookie
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleSessionHeaderTrackerTest {
    @Test fun eachClientReadsOnlyItsOwnResponsesFromASharedTransport() = runBlocking {
        val shared = HeaderTransport()
        val signIn = AppleSessionHeaderTracker(shared)
        val catalog = AppleSessionHeaderTracker(shared)

        signIn.execute(AppleHttpRequest("POST", "https://idmsa.apple.com/appleauth/auth/signin/complete"))
        val response = catalog.execute(AppleHttpRequest("POST", "https://p01-ckdatabasews.icloud.com/other"))

        assertEquals("scnt-1", signIn.headers.scnt)
        assertEquals("token-1", signIn.headers.sessionToken)
        assertNull(catalog.headers.scnt)
        assertEquals("token-2", catalog.headers.sessionToken)
        assertEquals("token-2", response.sessionHeaders.sessionToken)
    }

    @Test fun laterResponsesKeepHeadersTheyOmitAndRestoreSeedsTheSession() = runBlocking {
        val tracker = AppleSessionHeaderTracker(HeaderTransport())
        tracker.restore(SESSION)
        assertEquals(AppleSessionHeaders.of(SESSION), tracker.headers)

        tracker.execute(AppleHttpRequest("POST", "https://idmsa.apple.com/appleauth/auth/signin/complete"))
        assertEquals("IT", tracker.headers.accountCountryCode)
        assertEquals("scnt-1", tracker.headers.scnt)

        tracker.clear()
        assertEquals(AppleSessionHeaders(), tracker.headers)
    }

    private class HeaderTransport : AppleHttpTransport {
        private var calls = 0
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            calls += 1
            val headers = if (request.url.contains("signin/complete")) {
                mapOf("scnt" to "scnt-$calls", "x-apple-session-token" to "token-$calls")
            } else {
                mapOf("x-apple-session-token" to "token-$calls")
            }
            return AppleHttpResponse(200, headers, "{}")
        }
        override suspend fun stream(url: String, output: OutputStream) = error("Not used")
        override fun snapshotCookies() = emptyList<PersistedCookie>()
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
    }

    private companion object {
        val SESSION = AppleSessionSnapshot("person@example.com", "client", "IT", "session", "token", "trust", "123",
            emptyMap(), emptyList())
    }
}
