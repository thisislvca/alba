package dev.mela.protocol.photos

import dev.mela.engine.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedFormatCatalogTest {
    // Resource types, sizes, dimensions and durations reproduce the six-format
    // catalog observed on 2026-09-23. Record names, dates and download URLs are
    // synthetic; no account identifiers or signed URLs are included.
    private fun photos(): Map<String, CloudPhotoAsset> {
        val fixture = requireNotNull(javaClass.getResource("/photos/verified-format-catalog.json"))
        return CloudKitPhotoResponseParser().parse(fixture.readText()).photos.associateBy { it.fileName }
    }

    @Test fun cloudKitInt64VideoDurationsAreAlreadyMilliseconds() {
        val photos = photos()
        for (name in listOf("avc.mp4", "hevc.mov")) {
            val photo = photos.getValue(name)
            assertEquals(MediaKind.VIDEO, photo.kind)
            assertEquals(2000L, photo.durationMillis)
        }
        assertNull(photos.getValue("webp.webp").durationMillis)
    }

    @Test fun originalMimeTypesAreIndependentOfJpegPreviewsAndMp4Playback() {
        val photos = photos()
        val expected = mapOf(
            "jpeg.jpg" to "image/jpeg",
            "png.png" to "image/png",
            "heic.heic" to "image/heic",
            "webp.webp" to "image/webp",
            "avc.mp4" to "video/mp4",
            "hevc.mov" to "video/quicktime",
        )
        assertEquals(expected.keys, photos.keys)
        for ((name, mimeType) in expected) {
            val photo = photos.getValue(name)
            val stem = name.substringBefore('.')
            assertEquals(mimeType, photo.mimeType)
            assertEquals(640, photo.width)
            assertEquals(480, photo.height)
            assertEquals("https://cws.icloud-content.com/fixture/$stem/resJPEGThumbRes", photo.previewDownloadUrl)
            assertEquals("https://cws.icloud-content.com/fixture/$stem/resOriginalRes", photo.originalDownloadUrl)
        }
        assertEquals(MediaKind.PHOTO, photos.getValue("webp.webp").kind)
        assertEquals("https://cws.icloud-content.com/fixture/hevc/resVidMedRes", photos.getValue("hevc.mov").playbackUrl)
    }
}
