package me.lvca.alba.protocol.network

import org.junit.Assert.assertTrue
import org.junit.Test

class AppleEndpointPolicyTest {
    @Test
    fun `allows only HTTPS Apple service origins without URI credentials`() {
        assertAllowed("https://idmsa.apple.com/appleauth/auth")
        assertAllowed("https://p01-ckdatabasews.icloud.com/database/1")
        assertAllowed("https://cvws.icloud-content.com/B/signed")

        assertRejected("http://setup.icloud.com/setup")
        assertRejected("https://setup.icloud.com:8443/setup")
        assertRejected("https://user:secret@setup.icloud.com/setup")
        assertRejected("https://setup.icloud.com/setup#fragment")
        assertRejected("https://icloud.com.evil.example/setup")
        assertRejected("https://apple.com.evil.example/setup")
        // Host suffixes match whole labels only.
        assertRejected("https://noticloud.com/setup")
        assertRejected("https://127.0.0.1/setup")
    }

    private fun assertAllowed(url: String) {
        assertTrue(runCatching { AppleEndpointPolicy.requireAllowed(url) }.isSuccess)
    }

    private fun assertRejected(url: String) {
        assertTrue(runCatching { AppleEndpointPolicy.requireAllowed(url) }.isFailure)
    }
}
