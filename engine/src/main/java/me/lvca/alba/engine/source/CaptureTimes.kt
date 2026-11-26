package me.lvca.alba.engine.source

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
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
            if (mimeType.startsWith("video/")) {
                RandomAccessFile(file, "r").use { containerCreationTime(it.channel) }?.let { CaptureTime(it) }
            } else {
                exifTime(ExifInterface(file))
            }
        }.getOrNull()
        return resolve(embedded, dateTakenEpochMillis, dateModifiedEpochMillis)
    }

    /**
     * Reads the capture time of an original through its open [descriptor], as [read] does for a file. The descriptor
     * must be seekable; it is read by position and never closed here.
     */
    fun read(descriptor: FileDescriptor, mimeType: String, dateTakenEpochMillis: Long?, dateModifiedEpochMillis: Long?): CaptureTime? {
        val embedded = runCatching {
            if (mimeType.startsWith("video/")) {
                // Not closed: closing a stream made from a descriptor would close the descriptor.
                containerCreationTime(FileInputStream(descriptor).channel)?.let { CaptureTime(it) }
            } else {
                exifTime(ExifInterface(descriptor))
            }
        }.getOrNull()
        return resolve(embedded, dateTakenEpochMillis, dateModifiedEpochMillis)
    }

    private fun exifTime(exif: ExifInterface): CaptureTime? = exifTime(
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
        exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
        exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
    )

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
    fun containerCreationTime(file: File): Long? = RandomAccessFile(file, "r").use { containerCreationTime(it.channel) }

    /** [containerCreationTime] over [channel], read by position only, so its position is left as it was. */
    internal fun containerCreationTime(channel: FileChannel): Long? {
        val length = channel.size()
        val moov = findBox(channel, 0L, length, "moov") ?: return null
        val mvhd = findBox(channel, moov.first, moov.second, "mvhd") ?: return null
        val header = channel.readAt(mvhd.first, 1 + FLAGS_BYTES + Long.SIZE_BYTES.coerceAtMost((mvhd.second - mvhd.first - 1 - FLAGS_BYTES).toInt()))
        val version = header.get(0).toInt() and BYTE_MASK
        val seconds = if (version == LONG_TIMES_VERSION) header.getLong(1 + FLAGS_BYTES) else header.getInt(1 + FLAGS_BYTES).toLong() and UINT32_MASK
        return (seconds - SECONDS_1904_TO_1970).takeIf { seconds > 0L && it > 0L }?.times(MILLIS_PER_SECOND)
    }

    /** The content range (start, end) of the first [type] box between [start] and [end], scanning siblings only. */
    private fun findBox(channel: FileChannel, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var position = start
        while (position + BOX_HEADER <= end) {
            val head = channel.readAt(position, BOX_HEADER.toInt())
            var size = head.getInt(0).toLong() and UINT32_MASK
            val name = ByteArray(BOX_TYPE_BYTES).also { head.position(Int.SIZE_BYTES); head.get(it) }.toString(Charsets.ISO_8859_1)
            var header = BOX_HEADER
            if (size == 1L) {
                size = channel.readAt(position + BOX_HEADER, Long.SIZE_BYTES).getLong(0)
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
    private const val BYTE_MASK = 0xff
    private const val MILLIS_DIGITS = 3
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_1904_TO_1970 = 2_082_844_800L
}

/** Exactly [count] bytes at [position], big-endian, read without moving the channel's position. */
internal fun FileChannel.readAt(position: Long, count: Int): java.nio.ByteBuffer {
    val buffer = java.nio.ByteBuffer.allocate(count)
    while (buffer.hasRemaining()) {
        if (read(buffer, position + buffer.position()) < 0) throw java.io.EOFException("Ended before $count bytes at $position")
    }
    return buffer.also { it.flip() }
}
