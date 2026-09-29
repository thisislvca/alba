package dev.mela.protocol.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object AppleEndpointPolicy {
    fun requireAllowed(url: String): HttpUrl {
        val parsed = requireNotNull(url.toHttpUrlOrNull()) { "Apple returned an invalid service URL" }
        require(parsed.isHttps) { "Apple service URLs must use HTTPS" }
        require(parsed.port == 443) { "Apple service URLs must use port 443" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Apple service URLs cannot contain user information"
        }
        require(parsed.fragment == null) { "Apple service URLs cannot contain fragments" }
        require(isAllowedHost(parsed.host)) { "Apple returned an untrusted service host" }
        return parsed
    }

    fun isAllowedHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return normalized == "apple.com" ||
            normalized.endsWith(".apple.com") ||
            normalized == "icloud.com" ||
            normalized.endsWith(".icloud.com") ||
            normalized == "icloud.com.cn" ||
            normalized.endsWith(".icloud.com.cn") ||
            normalized == "icloud-content.com" ||
            normalized.endsWith(".icloud-content.com")
    }
}
