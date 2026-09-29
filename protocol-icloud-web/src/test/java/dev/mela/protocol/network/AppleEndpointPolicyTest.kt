package dev.mela.protocol.network

import org.junit.Assert.assertFalse
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
        assertRejected("https://127.0.0.1/setup")
    }

    @Test
    fun `host suffix matching does not accept lookalikes`() {
        assertTrue(AppleEndpointPolicy.isAllowedHost("p01.icloud.com"))
        assertTrue(AppleEndpointPolicy.isAllowedHost("idmsa.apple.com"))
        assertFalse(AppleEndpointPolicy.isAllowedHost("noticloud.com"))
        assertFalse(AppleEndpointPolicy.isAllowedHost("apple.com.evil.example"))
    }

    private fun assertAllowed(url: String) {
        assertTrue(runCatching { AppleEndpointPolicy.requireAllowed(url) }.isSuccess)
    }

    private fun assertRejected(url: String) {
        assertTrue(runCatching { AppleEndpointPolicy.requireAllowed(url) }.isFailure)
    }
}
