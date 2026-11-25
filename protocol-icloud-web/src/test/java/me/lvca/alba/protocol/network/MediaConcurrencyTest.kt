package me.lvca.alba.protocol.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaConcurrencyTest {
    /**
     * The grid downloads eight previews from Apple's CDN at once. Each call here holds its host slot until every
     * other has arrived, so with OkHttp's default of five calls per host this would never complete.
     */
    @Test fun eightPreviewDownloadsFromOneHostRunTogether() = runBlocking {
        val previews = 8
        val arrived = CountDownLatch(previews)
        val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            arrived.countDown()
            val together = arrived.await(5, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(if (together) 200 else 503).message("test")
                .body("jpeg".toResponseBody()).build()
        })
        withTimeout(10_000) {
            List(previews) { index ->
                async { runCatching { transport.stream("https://cvws.icloud-content.com/B/thumb$index", ByteArrayOutputStream()) } }
            }.awaitAll()
        }.forEach { result -> assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess) }
    }
}
