package me.lvca.alba.protocol.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UploadTransportTest {
    @Test
    fun `raw original uses observed plain content type and one-shot unchanged bytes`() = runBlocking {
        val original = byteArrayOf(0, 1, 2, 0xff.toByte(), 42)
        var opens = 0
        var requests = 0
        val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val request = chain.request()
            assertEquals("POST", request.method)
            val body = requireNotNull(request.body)
            assertEquals("text/plain", body.contentType().toString())
            assertEquals(original.size.toLong(), body.contentLength())
            assertTrue(body.isOneShot())
            val buffer = Buffer()
            body.writeTo(buffer)
            assertEquals(original.toList(), buffer.readByteArray().toList())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        })
        transport.executeRawUpload("https://cws.icloud-content.com/singleFileUpload", emptyMap(), original.size.toLong()) {
            opens++
            ByteArrayInputStream(original)
        }
        assertEquals(1, opens)
        assertEquals(1, requests)
    }

    @Test
    fun `a large original is streamed with a timeout per write and none on the whole call`() = runBlocking {
        val size = 4 * 1_024 * 1_024
        var reads = 0
        var largestRead = 0
        var timeouts: Triple<Int, Int, Long>? = null
        val transport = OkHttpAppleTransport(clientBuilder = OkHttpClient.Builder().addInterceptor { chain ->
            timeouts = Triple(chain.writeTimeoutMillis(), chain.readTimeoutMillis(), chain.call().timeout().timeoutNanos())
            val sink = Buffer()
            requireNotNull(chain.request().body).writeTo(sink)
            assertEquals(size.toLong(), sink.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        })
        transport.executeRawUpload("https://cws.icloud-content.com/singleFileUpload", emptyMap(), size.toLong()) {
            object : java.io.InputStream() {
                private var left = size
                override fun read(): Int = if (left-- > 0) 7 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (left == 0) return -1
                    val count = minOf(length, left)
                    left -= count
                    reads++
                    largestRead = maxOf(largestRead, count)
                    return count
                }
            }
        }
        val (write, read, call) = requireNotNull(timeouts)
        assertEquals(60_000, write)
        assertEquals("Apple may answer a large upload slowly", 300_000, read)
        assertEquals("No timeout covers the whole upload", 0L, call)
        assertTrue("The file is read in chunks, never whole", reads > 1 && largestRead < size)
    }
}
