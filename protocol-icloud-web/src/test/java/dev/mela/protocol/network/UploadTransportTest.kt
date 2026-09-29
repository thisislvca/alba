package dev.mela.protocol.network

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
