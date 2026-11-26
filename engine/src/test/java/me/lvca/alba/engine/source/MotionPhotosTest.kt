package me.lvca.alba.engine.source

import me.lvca.alba.engine.source.MotionPhotoFixtures.mp4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/**
 * Finding a motion photo's video, on synthetic files built as each camera writes them: Google Motion Photo v1 (Pixel,
 * newer Samsung, OnePlus, Oppo), the older MicroVideo tags (older Pixel, Xiaomi, Redmi, POCO) and Samsung's own trailer
 * (older Galaxy). Each is read as a stream, in chunks of every awkward size, as a phone file is.
 */
class MotionPhotosTest {
    @Test
    fun aPixelMotionPhotoGivesItsVideoAtTheOffsetCountedBackFromTheEnd() {
        val video = mp4(durationMs = 2_966, width = 1440, height = 1080)
        val file = MotionPhotoFixtures.pixelMotionPhoto(video, timestampUs = 1_483_000)

        val layout = MotionPhotoXmp.parse(requireNotNull(MotionPhotoXmp.findPacket(file)), file.size.toLong())
        assertEquals(MotionPhotoLayout(MotionPhotoFormat.MOTION_PHOTO_V1, (file.size - video.size).toLong(), video.size.toLong(), 1_483_000), layout)

        val found = requireNotNull(read(file))
        assertEquals(MotionPhotoFormat.MOTION_PHOTO_V1, found.format)
        assertEquals((file.size - video.size).toLong(), found.offset)
        assertArrayEquals(video, found.bytes)
        assertEquals(sha256(video), found.sha256Hex)
        assertEquals("Turned a quarter, so it displays portrait", Mp4Info(2_966_000, 1080, 1440), found.info)
        assertEquals(1_483_000, found.stillTimeUs)
    }

    @Test
    fun aSamsungMotionPhotoWithItemElementsAndPaddingIsRead() {
        val video = mp4(durationMs = 3_000, width = 1920, height = 1080, quarterTurn = false)
        val file = MotionPhotoFixtures.samsungMotionPhoto(video, padding = 8)

        val found = requireNotNull(read(file))
        assertEquals(MotionPhotoFormat.MOTION_PHOTO_V1, found.format)
        assertEquals((file.size - video.size).toLong(), found.offset)
        assertArrayEquals(video, found.bytes)
        assertEquals(Mp4Info(3_000_000, 1920, 1080), found.info)
        assertNull(found.presentationTimestampUs)
        assertEquals("Without a presentation time the still is the middle of the video", 1_500_000, found.stillTimeUs)
    }

    @Test
    fun microVideoTagsAsOlderPixelsAndXiaomiWriteThemAreRead() {
        val video = mp4(durationMs = 1_800)
        val file = MotionPhotoFixtures.microVideo(video, timestampUs = 900_000)

        val layout = requireNotNull(MotionPhotoXmp.parse(requireNotNull(MotionPhotoXmp.findPacket(file)), file.size.toLong()))
        assertEquals(MotionPhotoFormat.MICRO_VIDEO, layout.format)
        assertEquals((file.size - video.size).toLong(), layout.videoOffset)

        val found = requireNotNull(read(file))
        assertEquals(MotionPhotoFormat.MICRO_VIDEO, found.format)
        assertArrayEquals(video, found.bytes)
        assertEquals(900_000, found.stillTimeUs)
    }

    @Test
    fun samsungsOwnTrailerIsReadWithoutXmpAndTheSefFooterIsLeftOut() {
        val video = mp4(durationMs = 2_500, seed = 9)
        val file = MotionPhotoFixtures.samsungTrailer(video)
        val marker = String(file, Charsets.ISO_8859_1).indexOf("MotionPhoto_Data") + "MotionPhoto_Data".length

        val found = requireNotNull(read(file))
        assertEquals(MotionPhotoFormat.SAMSUNG_TRAILER, found.format)
        assertEquals(marker.toLong(), found.offset)
        assertArrayEquals("The MP4 alone: the SEF directory after it is not part of it", video, found.bytes)
        assertEquals(1_250_000, found.stillTimeUs)
    }

    @Test
    fun everyFormatIsReadTheSameWhateverChunksTheFileArrivesIn() {
        val files = listOf(
            MotionPhotoFixtures.pixelMotionPhoto(), MotionPhotoFixtures.samsungMotionPhoto(),
            MotionPhotoFixtures.microVideo(), MotionPhotoFixtures.samsungTrailer(),
        )
        for (file in files) {
            val whole = requireNotNull(read(file, chunk = file.size))
            for (chunk in listOf(1, 7, 64, 4_096)) {
                val parts = requireNotNull(read(file, chunk)) { "chunk $chunk" }
                assertEquals(whole.offset, parts.offset)
                assertArrayEquals("chunk $chunk", whole.bytes, parts.bytes)
            }
        }
    }

    @Test
    fun aPhotoWithoutAVideoOrWithMotionTurnedOffHasNone() {
        assertNull(read(MotionPhotoFixtures.plainPhoto()))
        assertNull(read(MotionPhotoFixtures.jpeg(null)))
        val off = MotionPhotoFixtures.pixelMotionPhoto().let { String(it, Charsets.ISO_8859_1) }
            .replace("GCamera:MotionPhoto=\"1\"", "GCamera:MotionPhoto=\"0\"").toByteArray(Charsets.ISO_8859_1)
        assertNull(read(off))
    }

    @Test
    fun anXmpOffsetThatHoldsNoMp4IsReportedByFormatAndNeverGuessed() {
        val file = MotionPhotoFixtures.microVideo(mp4()).let { bytes ->
            // The XMP says the video is 40 bytes longer than it is: the offset lands inside the JPEG.
            val text = String(bytes, Charsets.ISO_8859_1)
            val declared = Regex("MicroVideoOffset=\"(\\d+)\"").find(text)!!.groupValues[1]
            text.replace("MicroVideoOffset=\"$declared\"", "MicroVideoOffset=\"${declared.toInt() + 40}\"").toByteArray(Charsets.ISO_8859_1)
        }
        val reader = MotionPhotoReader(file.size.toLong())
        reader.write(file)

        assertNull(reader.finish())
        assertEquals(MotionPhotoFormat.MICRO_VIDEO, reader.declaredFormat)
        assertEquals(MotionPhotoReadFailure.NO_MP4_AT_OFFSET, reader.failure)
    }

    @Test
    fun xmpWithADoctypeIsNeverParsed() {
        val xmp = """<!DOCTYPE x [<!ENTITY a "b">]><x:xmpmeta xmlns:x="adobe:ns:meta/"></x:xmpmeta>"""
        assertNull(MotionPhotoXmp.parse(xmp, 1_000))
    }

    @Test
    fun anMp4sLengthStopsAtTheFirstBytesThatAreNotABox() {
        val video = mp4()
        assertEquals(video.size, Mp4Boxes.length(video + "SEFHjunk".toByteArray() + ByteArray(20), 0))
        assertEquals(video.size, Mp4Boxes.length(video, 0))
    }

    private fun read(file: ByteArray, chunk: Int = 4_096): MotionPhotoVideo? {
        val reader = MotionPhotoReader(file.size.toLong())
        var at = 0
        while (at < file.size) {
            val length = minOf(chunk, file.size - at)
            reader.write(file, at, length)
            at += length
        }
        return reader.finish()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
