package dev.mela.engine.source

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class UploadMediaFormatTest {
    private fun ftyp(vararg brands: String): ByteArray = ByteBuffer.allocate(12 + brands.size * 4).apply {
        putInt(capacity()); put("ftyp".toByteArray()); put(brands.first().toByteArray()); putInt(0)
        brands.drop(1).forEach { put(it.toByteArray()) }
    }.array()

    @Test fun validatesBytesInsteadOfTrustingNameOrMime() {
        assertEquals(UploadMediaFormat.JPEG, UploadMediaFormat.detect(byteArrayOf(-1, -40, -1)))
        assertEquals(UploadMediaFormat.PNG, UploadMediaFormat.detect(byteArrayOf(-119,80,78,71,13,10,26,10)))
        assertEquals(UploadMediaFormat.WEBP, UploadMediaFormat.detect("RIFF0000WEBP".toByteArray()))
        assertEquals(UploadMediaFormat.HEIC, UploadMediaFormat.detect(ftyp("heic", "mif1", "heic")))
        assertEquals(UploadMediaFormat.MOV, UploadMediaFormat.detect(ftyp("qt  ", "qt  ")))
        assertEquals(UploadMediaFormat.MP4, UploadMediaFormat.detect(ftyp("isom", "mp42")))
        for (invalid in listOf("GIF89a".toByteArray(), byteArrayOf(), ftyp("avif", "mif1"), ftyp("heic", "msf1", "hevc"))) {
            assertTrue(runCatching { UploadMediaFormat.detect(invalid) }.exceptionOrNull() is UnsupportedUploadException)
        }
    }
    @Test fun retainsVerifiedSuffixAndCorrectsMisleadingSuffix() {
        assertEquals("original.HEIF", UploadMediaFormat.HEIC.fileName("original.HEIF"))
        assertEquals("movie.mov", UploadMediaFormat.MOV.fileName("folder/movie.mov"))
        assertEquals("photo.png", UploadMediaFormat.PNG.fileName("photo.jpg"))
        assertFalse(UploadMediaFormat.isCandidate("image/gif"))
        assertTrue(UploadMediaFormat.isCandidate("video/quicktime"))
    }
}
