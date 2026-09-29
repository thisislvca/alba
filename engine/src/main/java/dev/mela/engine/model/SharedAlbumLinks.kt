package dev.mela.engine.model

import java.net.URI

/** Only recognized first-party album routes can reach invitation resolution. */
object SharedAlbumLinks {
    fun token(value: String): String? = runCatching {
        require(value.length <= 8192)
        val uri = URI(value.trim())
        require(uri.scheme == "https" && uri.host in setOf("icloud.com", "www.icloud.com", "photos.icloud.com", "icloud.com.cn", "www.icloud.com.cn", "photos.icloud.com.cn"))
        require(uri.rawUserInfo == null && uri.port in setOf(-1, 443))
        val path = uri.rawPath.orEmpty()
        val token = if (path.startsWith("/shared/album/")) {
            require(uri.rawFragment == null)
            val segment = path.removePrefix("/shared/album/").removeSuffix("/")
            if (segment.isNotEmpty()) { require(uri.rawQuery == null); segment }
            else { require(uri.rawQuery?.startsWith("guid=") == true); uri.rawQuery.removePrefix("guid=") }
        } else {
            require(path.removeSuffix("/") == "/photos" && uri.rawQuery == null)
            Regex("^/?(?:sharedalbums/)?sc,([A-Za-z0-9_-]{1,4096})/?$").matchEntire(uri.rawFragment.orEmpty())!!.groupValues[1]
        }
        require(token.matches(Regex("[A-Za-z0-9_-]{1,4096}")))
        token
    }.getOrNull()

    fun extract(text: String): String? {
        if (text.length > 8192) return null
        val exact = text.trim()
        if (token(exact) != null) return exact
        val urls = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
        return urls.singleOrNull()?.takeIf { token(it) != null }
    }
}
