package me.lvca.alba.engine.source

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.OffsetDateTime

/** Which date an original is uploaded with: its own capture time first, then MediaStore's, never a later edit. */
@RunWith(AndroidJUnit4::class)
class CaptureTimesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun exifCaptureTimeWithItsOffsetIsTheInstantItWasTaken() {
        val tokyo = CaptureTimes.exifTime("2023:07:01 12:00:00", "+09:00", "25")
        assertEquals(OffsetDateTime.parse("2023-07-01T12:00:00.250+09:00").toInstant().toEpochMilli(), tokyo?.epochMillis)
        assertEquals(540, tokyo?.offsetMinutes)
        assertEquals(-300, CaptureTimes.exifTime("2023:01:05 08:30:00", "-05:00")?.offsetMinutes)
    }

    @Test
    fun aLocalExifTimeWithoutAnOffsetOrAMalformedOneIsNotUsed() {
        assertNull(CaptureTimes.exifTime("2023:07:01 12:00:00", null))
        assertNull(CaptureTimes.exifTime("2023:07:01 12:00:00", "Tokyo"))
        assertNull(CaptureTimes.exifTime("0000:00:00 00:00:00", "+09:00"))
        assertNull(CaptureTimes.exifTime("    ", "+09:00"))
    }

    @Test
    fun mediaStoreDateTakenComesBeforeTheModificationDate() {
        assertEquals(CaptureTime(200), CaptureTimes.resolve(null, dateTakenEpochMillis = 200, dateModifiedEpochMillis = 900))
        assertEquals(CaptureTime(900), CaptureTimes.resolve(null, dateTakenEpochMillis = 0, dateModifiedEpochMillis = 900))
        assertEquals(CaptureTime(50, 60), CaptureTimes.resolve(CaptureTime(50, 60), 200, 900))
        assertNull(CaptureTimes.resolve(null, null, null))
    }

    @Test
    fun aJpegIsDatedByItsExifReadWithoutChangingTheFile() {
        val file = folder.newFile("photo.jpg").apply { writeBytes(MINIMAL_JPEG) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:12:31 23:59:58")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+01:00")
            saveAttributes()
        }
        val bytes = file.readBytes()

        val time = CaptureTimes.read(file, "image/jpeg", dateTakenEpochMillis = 5, dateModifiedEpochMillis = 9)

        assertEquals(CaptureTime(OffsetDateTime.parse("2019-12-31T23:59:58+01:00").toInstant().toEpochMilli(), 60), time)
        assertEquals("Reading the date never rewrites the original", bytes.toList(), file.readBytes().toList())
    }

    @Test
    fun aJpegWithoutExifFallsBackToMediaStore() {
        val file = folder.newFile("plain.jpg").apply { writeBytes(MINIMAL_JPEG) }
        assertEquals(CaptureTime(5), CaptureTimes.read(file, "image/jpeg", dateTakenEpochMillis = 5, dateModifiedEpochMillis = 9))
    }

    @Test
    fun aVideoIsDatedByItsMovieHeaderWhereverTheHeaderIs() {
        val created = OffsetDateTime.parse("2024-03-10T18:00:00Z").toInstant().epochSecond
        val atStart = mp4(folder.newFile("start.mp4"), created, version = 0, mdatFirst = false)
        val atEnd = mp4(folder.newFile("end.mp4"), created, version = 1, mdatFirst = true)

        assertEquals(CaptureTime(created * 1_000), CaptureTimes.read(atStart, "video/mp4", 5, 9))
        assertEquals(CaptureTime(created * 1_000), CaptureTimes.read(atEnd, "video/quicktime", 5, 9))
    }

    @Test
    fun aVideoWithAnUnsetCreationTimeFallsBackToMediaStore() {
        val unset = mp4(folder.newFile("unset.mp4"), createdEpochSeconds = null, version = 0, mdatFirst = false)
        assertEquals(CaptureTime(5), CaptureTimes.read(unset, "video/mp4", dateTakenEpochMillis = 5, dateModifiedEpochMillis = 9))
        val notMp4 = folder.newFile("garbage.mp4").apply { writeBytes(ByteArray(64) { 0x41 }) }
        assertEquals(CaptureTime(9), CaptureTimes.read(notMp4, "video/mp4", dateTakenEpochMillis = null, dateModifiedEpochMillis = 9))
    }

    /** ftyp, moov with an mvhd, and an mdat placed before or after moov. A null creation time writes zero. */
    private fun mp4(file: File, createdEpochSeconds: Long?, version: Int, mdatFirst: Boolean): File {
        val since1904 = createdEpochSeconds?.plus(2_082_844_800L) ?: 0L
        val mvhdBody = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeByte(version); write(byteArrayOf(0, 0, 0))
                if (version == 1) { writeLong(since1904); writeLong(since1904) } else { writeInt(since1904.toInt()); writeInt(since1904.toInt()) }
                write(ByteArray(80))
            }
        }.toByteArray()
        val moov = box("moov", box("mvhd", mvhdBody))
        val mdat = box("mdat", ByteArray(1_024) { 7 })
        file.writeBytes(box("ftyp", "isom".toByteArray() + ByteArray(4)) + if (mdatFirst) mdat + moov else moov + mdat)
        return file
    }

    private fun box(type: String, body: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply { writeInt(body.size + 8); write(type.toByteArray(Charsets.ISO_8859_1)); write(body) }
    }.toByteArray()

    private companion object {
        /** SOI, a JFIF APP0, a start of scan with one byte of data, EOI: enough for ExifInterface to read and write. */
        val MINIMAL_JPEG = ints(
            0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0x00, 0xFF, 0xD9,
        ).map { it.toByte() }.toByteArray()

        fun ints(vararg values: Int): IntArray = values
    }
}
