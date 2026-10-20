package me.lvca.alba.protocol.network

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MediaRangeTest {
    private fun response(request: Request, code: Int, headers: Map<String, String> = emptyMap()) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .body("abc".toResponseBody()).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    @Test fun readsRequestedRangeWithoutDownloadingWholeFile() = runBlocking {
        val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=10-12", chain.request().header("Range"))
            response(chain.request(), 206, mapOf("Content-Range" to "bytes 10-12/100000000"))
        })
        transport.openRange("https://cws.icloud-content.com/movie", 10, 3).use { assertEquals("abc", it.input.reader().readText()) }
    }
    @Test fun rejectsRedirectToUntrustedHost() = runBlocking {
        var calls = 0
        val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            calls++; response(chain.request(), 302, mapOf("Location" to "https://example.com/movie"))
        })
        assertTrue(runCatching { transport.openRange("https://cws.icloud-content.com/movie", 0, -1) }.isFailure)
        assertEquals(1, calls)
    }
    @Test fun streamAndRangeFollowAllowedRedirectsUpToTheLimit() = runBlocking {
        val hops = mutableListOf<String>()
        val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            hops += path
            when {
                path == "/start" -> response(chain.request(), 302, mapOf("Location" to "/final"))
                path == "/final" -> response(chain.request(), 206, mapOf("Content-Range" to "bytes 0-2/3"))
                else -> response(chain.request(), 307, mapOf("Location" to "/loop"))
            }
        })
        val output = java.io.ByteArrayOutputStream()
        transport.stream("https://cws.icloud-content.com/start", output)
        assertEquals("abc", output.toString())
        transport.openRange("https://cws.icloud-content.com/start", 0, 3).use { assertEquals("abc", it.input.reader().readText()) }
        assertEquals(listOf("/start", "/final", "/start", "/final"), hops)

        hops.clear()
        assertTrue(runCatching { transport.stream("https://cws.icloud-content.com/loop", output) }.exceptionOrNull() is java.io.IOException)
        assertEquals(6, hops.size)
    }
    @Test fun rejectsWrongRangeAndReportsExpiredResource() = runBlocking {
        for (code in listOf(200, 206, 403)) {
            val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
                response(chain.request(), code, mapOf("Content-Range" to "bytes 0-2/100"))
            })
            assertTrue(runCatching { transport.openRange("https://cws.icloud-content.com/movie", 10, 3) }.isFailure)
        }
    }
}
