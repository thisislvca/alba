package dev.mela.protocol.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppleSessionCodecTest {
    @Test
    fun `session codec round trips all resumable state`() {
        val snapshot = sampleSnapshot()

        val encoded = AppleSessionCodec.encode(snapshot)
        val decoded = AppleSessionCodec.decode(encoded)

        assertEquals(snapshot, decoded)
        assertFalse(encoded.contains("password", ignoreCase = true))
    }

    private fun sampleSnapshot() = AppleSessionSnapshot(
        accountName = "person@example.com",
        clientId = "client-id",
        accountCountryCode = "IT",
        sessionId = "session-id",
        sessionToken = "session-token",
        trustToken = "trust-token",
        dsid = "123456",
        webservices = mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com"),
        cookies = listOf(
            PersistedCookie(
                name = "X-APPLE-WEBAUTH-TOKEN",
                value = "cookie-value",
                domain = ".icloud.com",
                path = "/",
                expiresAtEpochMillis = 2_000_000_000_000L,
                secure = true,
                httpOnly = true,
                hostOnly = false,
            ),
        ),
    )
}
