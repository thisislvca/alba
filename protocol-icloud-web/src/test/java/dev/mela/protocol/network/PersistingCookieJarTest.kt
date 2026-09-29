package dev.mela.protocol.network

import dev.mela.protocol.account.PersistedCookie
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistingCookieJarTest {
    @Test
    fun `restored domain cookie is sent only to matching secure hosts`() {
        val jar = PersistingCookieJar()
        jar.restore(
            listOf(
                PersistedCookie(
                    name = "X-APPLE-WEBAUTH-TOKEN",
                    value = "secret",
                    domain = "icloud.com",
                    path = "/",
                    expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
                    secure = true,
                    httpOnly = true,
                    hostOnly = false,
                ),
            ),
        )

        assertEquals(1, jar.loadForRequest("https://setup.icloud.com/setup".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("http://setup.icloud.com/setup".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://example.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `new response replaces the same cookie identity`() {
        val jar = PersistingCookieJar()
        val url = "https://setup.icloud.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("first")))
        jar.saveFromResponse(url, listOf(cookie("second")))

        assertEquals("second", jar.loadForRequest(url).single().value)
    }

    @Test
    fun `restore rejects cookies scoped outside Apple hosts`() {
        val jar = PersistingCookieJar()
        jar.restore(
            listOf(
                PersistedCookie(
                    name = "session",
                    value = "must-not-leak",
                    domain = "com",
                    path = "/",
                    expiresAtEpochMillis = null,
                    secure = true,
                    httpOnly = true,
                    hostOnly = false,
                ),
            ),
        )

        assertTrue(jar.loadForRequest("https://setup.icloud.com/".toHttpUrl()).isEmpty())
    }

    private fun cookie(value: String): Cookie = Cookie.Builder()
        .name("session")
        .value(value)
        .hostOnlyDomain("setup.icloud.com")
        .path("/")
        .secure()
        .build()
}
