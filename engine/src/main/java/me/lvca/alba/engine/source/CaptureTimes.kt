package me.lvca.alba.engine.source

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * When an item was captured, for the date iCloud gives an upload. [offsetMinutes] is the capture's offset east of UTC
 * when the file records it; null means the file does not say, and the upload uses the phone's zone as iCloud.com does.
 */
data class CaptureTime(
    val epochMillis: Long,
    val offsetMinutes: Int? = null,
)

/**
 * Reads the capture time of a staged original, read-only: EXIF DateTimeOriginal with OffsetTimeOriginal for a photo,
 * or the container's creation time for a video, then MediaStore's DATE_TAKEN, then the file's DATE_MODIFIED. A local
 * EXIF time without an offset is not used on its own: MediaStore already resolved it into DATE_TAKEN.
 */
object CaptureTimes {
    fun read(file: File, mimeType: String, dateTakenEpochMillis: Long?, dateModifiedEpochMillis: Long?): CaptureTime? {
        val embedded = runCatching {
            if (mimeType.startsWith("video/")) containerCreationTime(file)?.let { CaptureTime(it) }
            else ExifInterface(file).let { exif ->
                exifTime(
                    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                    exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
                )
            }
        }.getOrNull()
        return resolve(embedded, dateTakenEpochMillis, dateModifiedEpochMillis)
    }

    /** The first date known, in order of how well it describes the capture. */
    fun resolve(embedded: CaptureTime?, dateTakenEpochMillis: Long?, dateModifiedEpochMillis: Long?): CaptureTime? =
        embedded
            ?: dateTakenEpochMillis?.takeIf { it > 0L }?.let { CaptureTime(it) }
            ?: dateModifiedEpochMillis?.takeIf { it > 0L }?.let { CaptureTime(it) }

    /** EXIF's local capture time and its offset, as an instant; null without both or when either is malformed. */
    fun exifTime(dateTimeOriginal: String?, offsetTimeOriginal: String?, subsecTimeOriginal: String? = null): CaptureTime? {
        val local = dateTimeOriginal?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val offset = offsetTimeOriginal?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() } ?: return null
        val time = runCatching { LocalDateTime.parse(local, EXIF_DATE_TIME) }.getOrNull() ?: return null
        val millis = subsecTimeOriginal?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.padEnd(MILLIS_DIGITS, '0')?.take(MILLIS_DIGITS)?.toLong() ?: 0L
        return CaptureTime(time.toInstant(offset).toEpochMilli() + millis, offset.totalSeconds / SECONDS_PER_MINUTE)
    }

    /**
     * The creation time in an MP4 or QuickTime file's movie header, in UTC as those formats define it. Null when the
     * file has none, it is unset, or it is before 1970: some cameras write zero.
     */
    fun containerCreationTime(file: File): Long? = RandomAccessFile(file, "r").use { input ->
        val moov = findBox(input, 0L, input.length(), "moov") ?: return null
        val mvhd = findBox(input, moov.first, moov.second, "mvhd") ?: return null
        input.seek(mvhd.first)
        val version = input.readUnsignedByte()
        input.skipBytes(FLAGS_BYTES)
        val seconds = if (version == LONG_TIMES_VERSION) input.readLong() else input.readInt().toLong() and UINT32_MASK
        (seconds - SECONDS_1904_TO_1970).takeIf { seconds > 0L && it > 0L }?.times(MILLIS_PER_SECOND)
    }

    /** The content range (start, end) of the first [type] box between [start] and [end], scanning siblings only. */
    private fun findBox(input: RandomAccessFile, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var position = start
        while (position + BOX_HEADER <= end) {
            input.seek(position)
            var size = input.readInt().toLong() and UINT32_MASK
            val name = ByteArray(BOX_TYPE_BYTES).also(input::readFully).toString(Charsets.ISO_8859_1)
            var header = BOX_HEADER
            if (size == 1L) {
                size = input.readLong()
                header += Long.SIZE_BYTES
            } else if (size == 0L) {
                size = end - position
            }
            if (size < header || position + size > end) return null
            if (name == type) return (position + header) to (position + size)
            position += size
        }
        return null
    }

    private val EXIF_DATE_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private const val BOX_HEADER = 8L
    private const val BOX_TYPE_BYTES = 4
    private const val FLAGS_BYTES = 3
    private const val LONG_TIMES_VERSION = 1
    private const val UINT32_MASK = 0xffffffffL
    private const val MILLIS_DIGITS = 3
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_1904_TO_1970 = 2_082_844_800L
}
