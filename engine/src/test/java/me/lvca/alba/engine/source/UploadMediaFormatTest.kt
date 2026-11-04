package me.lvca.alba.engine.source

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class UploadMediaFormatTest {
    private fun ftyp(vararg brands: String): ByteArray = ByteBuffer.allocate(12 + brands.size * 4).apply {
        putInt(capacity()); put("ftyp".toByteArray()); put(brands.first().toByteArray()); putInt(0)
        brands.drop(1).forEach { put(it.toByteArray()) }
    }.array()

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("upload-formats/$name")) { name }.use { it.readBytes() }

    private fun prefix(name: String) = fixture(name).let { it.copyOf(minOf(it.size, 4096)) }

    private fun reason(bytes: ByteArray) =
        (runCatching { UploadMediaFormat.detect(bytes) }.exceptionOrNull() as UnsupportedUploadException).message

    @Test fun validatesBytesInsteadOfTrustingNameOrMime() {
        assertEquals(UploadMediaFormat.JPEG, UploadMediaFormat.detect(byteArrayOf(-1, -40, -1)))
        assertEquals(UploadMediaFormat.PNG, UploadMediaFormat.detect(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        assertEquals(UploadMediaFormat.WEBP, UploadMediaFormat.detect("RIFF0000WEBP".toByteArray()))
        assertEquals(UploadMediaFormat.HEIC, UploadMediaFormat.detect(ftyp("heic", "mif1", "heic")))
        assertEquals(UploadMediaFormat.MOV, UploadMediaFormat.detect(ftyp("qt  ", "qt  ")))
        assertEquals(UploadMediaFormat.MP4, UploadMediaFormat.detect(ftyp("isom", "mp42")))
        assertEquals(UploadMediaFormat.MP4, UploadMediaFormat.detect(ftyp("iso6", "iso6", "mp41")))
        for (invalid in listOf("GIF89a".toByteArray(), byteArrayOf(), ftyp("avif", "mif1"), ftyp("heic", "msf1", "hevc"))) {
            assertTrue(runCatching { UploadMediaFormat.detect(invalid) }.exceptionOrNull() is UnsupportedUploadException)
        }
    }

    @Test fun refusalsNameWhyTheFormatIsRefused() {
        assertEquals(UnsupportedUploadException.AVIF, reason(ftyp("avif", "mif1")))
        assertEquals(UnsupportedUploadException.IMAGE_SEQUENCE, reason(ftyp("heic", "msf1", "hevc")))
        assertEquals(UnsupportedUploadException.GIF, reason("GIF89a".toByteArray()))
        assertEquals(UnsupportedUploadException.MESSAGE, reason("BM".toByteArray()))
    }

    @Test fun formatsICloudWebDoesNotTakeAreRecognisedFromTheirOwnBytes() {
        assertEquals(UnsupportedUploadException.GIF, reason(prefix("still.gif")))
        assertEquals(UnsupportedUploadException.RAW, reason(prefix("dng.dng")))
        assertEquals(UnsupportedUploadException.TIFF, reason(prefix("tiff.tif")))
        // 3GP lists isom among its brands, so its major brand decides; M4V likewise.
        assertEquals(UnsupportedUploadException.VIDEO_CONTAINER, reason(prefix("clip.3gp")))
        assertEquals(UnsupportedUploadException.VIDEO_CONTAINER, reason(prefix("m4v.m4v")))
        assertEquals(UnsupportedUploadException.VIDEO_CONTAINER, reason(prefix("clip.webm")))
        assertEquals(UnsupportedUploadException.RAW, reason(ftyp("crx ", "crx ", "isom")))
        assertEquals(UploadMediaFormat.MP4, UploadMediaFormat.detect(prefix("clip.mp4")))
    }

    @Test fun aHeifThatNamesOnlyTheGenericBrandIsReadByItsItemTypes() {
        assertEquals(setOf("grid", "hvc1", "Exif"), HeifItems.types(prefix("mif1-hevc.heif"), 24))
        assertEquals(UploadMediaFormat.HEIC, UploadMediaFormat.detect(prefix("mif1-hevc.heif")))
        assertEquals(UnsupportedUploadException.AVIF, reason(prefix("mif1-av1.heif")))
        // Without a readable item list there is nothing to go on, so it is refused rather than guessed.
        assertEquals(UnsupportedUploadException.MESSAGE, reason(ftyp("mif1", "mif1", "miaf")))
    }

    @Test fun motionPhotosAreTheirStillFormatSoTheEmbeddedVideoTravelsWithTheOriginal() {
        // A Google Motion Photo is a JPEG with its MP4 appended; a Samsung HEIC keeps it in a trailing box.
        assertEquals(UploadMediaFormat.JPEG, UploadMediaFormat.detect(prefix("PXL_20240501_101010123.MP.jpg")))
        assertEquals("PXL_20240501_101010123.MP.jpg", UploadMediaFormat.JPEG.fileName("PXL_20240501_101010123.MP.jpg"))
        assertEquals(UploadMediaFormat.HEIC, UploadMediaFormat.detect(prefix("motion.heic")))
    }

    @Test fun mediaStoreTypesTheUploaderCannotTakeAreSkippedWithTheirReason() {
        val reasons = mapOf(
            "image/gif" to UnsupportedUploadException.GIF,
            "image/x-adobe-dng" to UnsupportedUploadException.RAW,
            "image/x-sony-arw" to UnsupportedUploadException.RAW,
            "image/tiff" to UnsupportedUploadException.TIFF,
            "image/avif" to UnsupportedUploadException.AVIF,
            "image/heif-sequence" to UnsupportedUploadException.IMAGE_SEQUENCE,
            "video/3gpp" to UnsupportedUploadException.VIDEO_CONTAINER,
            "video/webm" to UnsupportedUploadException.VIDEO_CONTAINER,
            "video/x-matroska" to UnsupportedUploadException.VIDEO_CONTAINER,
            "image/bmp" to UnsupportedUploadException.NOT_ACCEPTED,
        )
        reasons.forEach { (mime, reason) ->
            assertFalse(mime, UploadMediaFormat.isCandidate(mime))
            assertEquals(mime, reason, UploadMediaFormat.unsupportedReason(mime))
        }
        listOf("image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp", "video/mp4", "video/quicktime")
            .forEach { assertTrue(it, UploadMediaFormat.isCandidate(it)) }
    }

    @Test fun retainsVerifiedSuffixAndCorrectsMisleadingSuffix() {
        assertEquals("original.HEIF", UploadMediaFormat.HEIC.fileName("original.HEIF"))
        assertEquals("movie.mov", UploadMediaFormat.MOV.fileName("folder/movie.mov"))
        assertEquals("photo.png", UploadMediaFormat.PNG.fileName("photo.jpg"))
        assertFalse(UploadMediaFormat.isCandidate("image/gif"))
        assertTrue(UploadMediaFormat.isCandidate("video/quicktime"))
    }
}
