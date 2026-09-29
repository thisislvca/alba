package dev.mela.protocol.network

import dev.mela.protocol.account.PersistedCookie
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class PersistingCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.forEach { incoming ->
            this.cookies.removeAll { existing -> existing.identity == incoming.identity }
            if (incoming.expiresAt > now) this.cookies += incoming
        }
        this.cookies.removeAll { it.expiresAt <= now }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { cookie -> cookie.matches(url) }
    }

    @Synchronized
    fun snapshot(): List<PersistedCookie> = cookies.map { cookie ->
        PersistedCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expiresAtEpochMillis = cookie.expiresAt.takeIf { cookie.persistent },
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly,
        )
    }

    @Synchronized
    fun restore(values: List<PersistedCookie>) {
        cookies.clear()
        values.mapNotNullTo(cookies) { value -> value.toOkHttpCookie() }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    private val Cookie.identity: Triple<String, String, String>
        get() = Triple(name, domain, path)

    private fun PersistedCookie.toOkHttpCookie(): Cookie? = runCatching {
        require(AppleEndpointPolicy.isAllowedHost(domain.trimStart('.'))) {
            "Persisted cookie has an untrusted domain"
        }
        Cookie.Builder()
            .name(name)
            .value(value)
            .path(path)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                expiresAtEpochMillis?.let(::expiresAt)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()
}
