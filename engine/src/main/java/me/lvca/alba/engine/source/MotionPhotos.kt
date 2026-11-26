package me.lvca.alba.engine.source

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** The motion photo formats Alba can read the video out of. Their ids go to diagnostics; keep them. */
enum class MotionPhotoFormat(val id: String) {
    /** Google's Motion Photo v1 container (`Camera:MotionPhoto` and a `Container:Directory`): Pixel, newer Samsung, OnePlus. */
    MOTION_PHOTO_V1("motion_photo_v1"),

    /** Google's older MicroVideo tags (`GCamera:MicroVideoOffset`): older Pixels ("MVIMG"), Xiaomi, Redmi and POCO. */
    MICRO_VIDEO("micro_video"),

    /** Samsung's own trailer: a `MotionPhoto_Data` block holding the MP4 after the image, older Galaxy phones. */
    SAMSUNG_TRAILER("samsung_trailer"),
}

/**
 * Where a motion photo's XMP says its video is: [videoOffset] from the start of the file, [videoLength] bytes long.
 * [slack] is padding the XMP declares around the video, tried when the expected offset holds no MP4.
 */
data class MotionPhotoLayout(
    val format: MotionPhotoFormat,
    val videoOffset: Long,
    val videoLength: Long,
    val presentationTimestampUs: Long?,
    val slack: Long = 0,
)

/**
 * Reads a motion photo's XMP: Google's Motion Photo v1 container, as Pixel, Samsung and others write it, and the older
 * MicroVideo tags. Pure, so it is tested on the JVM with synthetic XMP; it never reads more than the packet it is given.
 */
object MotionPhotoXmp {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"

    /** The XMP packet in [head], the first bytes of a JPEG or HEIC, or null when it has none. */
    fun findPacket(head: ByteArray, length: Int = head.size): String? {
        val text = String(head, 0, length, Charsets.ISO_8859_1)
        val start = text.indexOf("<x:xmpmeta").takeIf { it >= 0 } ?: return null
        val end = text.indexOf("</x:xmpmeta>", start).takeIf { it >= 0 } ?: return null
        return String(head, start, end + "</x:xmpmeta>".length - start, Charsets.UTF_8)
    }

    /** Whether [head] holds a complete XMP packet, so the rest of the file need not be kept to read it. */
    fun hasCompletePacket(head: ByteArray, length: Int): Boolean =
        String(head, 0, length, Charsets.ISO_8859_1).let { it.contains("<x:xmpmeta") && it.contains("</x:xmpmeta>") }

    /** Where the video of a file [fileLength] bytes long is, by its [xmp]; null when the XMP describes no video. */
    fun parse(xmp: String, fileLength: Long): MotionPhotoLayout? {
        val elements = document(xmp)?.descendants() ?: return null
        fun property(namespace: String, name: String): String? = elements.firstNotNullOfOrNull { element ->
            element.getAttributeNS(namespace, name).takeIf { it.isNotEmpty() }
                ?: element.takeIf { it.namespaceURI == namespace && it.localName == name }?.textContent?.trim()
        }
        val timestamp = (property(CAMERA, "MotionPhotoPresentationTimestampUs") ?: property(CAMERA, "MicroVideoPresentationTimestampUs"))
            ?.toLongOrNull()?.takeIf { it >= 0 }
        val container = if (property(CAMERA, "MotionPhoto") == "0") null else containerLayout(elements, fileLength, timestamp)
        return container ?: microVideoLayout(property(CAMERA, "MicroVideo"), property(CAMERA, "MicroVideoOffset"), fileLength, timestamp)
    }

    /** Motion Photo v1: the video item's offset is the file's length less its own and every later item's. */
    private fun containerLayout(elements: List<Element>, fileLength: Long, timestamp: Long?): MotionPhotoLayout? {
        val items = elements.filter { it.namespaceURI == CONTAINER && it.localName == "Item" }.map { item ->
            fun field(name: String) = item.getAttributeNS(ITEM, name).takeIf { it.isNotEmpty() }
                ?: item.descendants().firstOrNull { it.namespaceURI == ITEM && it.localName == name }?.textContent?.trim()
            ContainerItem(
                field("Mime").orEmpty(), field("Semantic").orEmpty(),
                field("Length")?.toLongOrNull() ?: 0, field("Padding")?.toLongOrNull() ?: 0,
            )
        }
        val video = items.indexOfFirst { it.semantic == "MotionPhoto" && it.mime.startsWith("video/") }
        val after = if (video > 0) items.drop(video) else return null
        val offset = fileLength - after.sumOf { it.length }
        if (after.any { it.length <= 0 } || offset <= 0) return null
        return MotionPhotoLayout(
            MotionPhotoFormat.MOTION_PHOTO_V1, offset, items[video].length, timestamp, items[video].padding + items[video - 1].padding,
        )
    }

    /** MicroVideo: the offset is counted back from the end of the file, and is also the video's length. */
    private fun microVideoLayout(flag: String?, fromEndText: String?, fileLength: Long, timestamp: Long?): MotionPhotoLayout? {
        val fromEnd = fromEndText?.toLongOrNull()?.takeIf { flag == "1" && it in 1 until fileLength } ?: return null
        return MotionPhotoLayout(MotionPhotoFormat.MICRO_VIDEO, fileLength - fromEnd, fromEnd, timestamp)
    }

    private data class ContainerItem(val mime: String, val semantic: String, val length: Long, val padding: Long)

    private fun document(xmp: String): Element? {
        // An XMP packet never needs a DTD; refusing one keeps entity expansion out of the parser.
        if (xmp.contains("<!DOCTYPE", ignoreCase = true) || xmp.contains("<!ENTITY", ignoreCase = true)) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmp.toByteArray(Charsets.UTF_8))).documentElement
        }.getOrNull()
    }

    private fun Element.descendants(): List<Element> = buildList {
        fun visit(node: Node) {
            if (node is Element) add(node)
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(this@descendants)
    }
}

/** What an MP4 says about its video: how long it plays and its picture's size as displayed. */
data class Mp4Info(val durationUs: Long, val width: Int, val height: Int)

/** Reads an ISO base media file (MP4, QuickTime) by its boxes: no decoder, so it runs on the JVM. */
object Mp4Boxes {
    /** Whether [bytes] holds an MP4 `ftyp` box at [offset]. */
    fun startsAt(bytes: ByteArray, offset: Int): Boolean =
        offset >= 0 && offset + HEADER <= bytes.size && String(bytes, offset + TYPE_AT, TYPE_BYTES, Charsets.ISO_8859_1) == "ftyp"

    /**
     * How many bytes the top-level boxes from [offset] take, stopping at the first that does not fit within [limit]
     * bytes or is not a box: the length of the MP4 that starts there. A trailer after it is not counted.
     */
    fun length(bytes: ByteArray, offset: Int, limit: Int = bytes.size - offset): Int {
        val end = offset + minOf(limit, bytes.size - offset)
        return (children(bytes, offset, end).lastOrNull()?.end ?: offset) - offset
    }

    /** The movie's duration and its first video track's displayed size; null when [mp4] has no readable movie header. */
    fun info(mp4: ByteArray): Mp4Info? {
        val moov = children(mp4, 0, mp4.size).firstOrNull { it.type == "moov" } ?: return null
        val boxes = children(mp4, moov.body, moov.end)
        val mvhd = boxes.firstOrNull { it.type == "mvhd" } ?: return null
        val wide = mp4[mvhd.body].toInt() == 1
        val timescale = readUInt32(mp4, mvhd.body + if (wide) MVHD_V1_TIMESCALE else MVHD_V0_TIMESCALE)
        val duration = if (wide) readUInt64(mp4, mvhd.body + MVHD_V1_DURATION) else readUInt32(mp4, mvhd.body + MVHD_V0_DURATION)
        if (timescale <= 0) return null
        val durationUs = duration * MICROS_PER_SECOND / timescale
        val (width, height) = videoTrack(mp4, boxes)?.let { displayedSize(mp4, it) } ?: (0 to 0)
        return Mp4Info(durationUs, width, height)
    }

    /** The first track whose handler is `vide`. */
    private fun videoTrack(mp4: ByteArray, boxes: List<Box>): Box? = boxes.filter { it.type == "trak" }.firstOrNull { trak ->
        val mdia = children(mp4, trak.body, trak.end).firstOrNull { it.type == "mdia" }
        val hdlr = mdia?.let { children(mp4, it.body, it.end).firstOrNull { box -> box.type == "hdlr" } }
        hdlr != null && hdlr.body + HDLR_TYPE_AT + TYPE_BYTES <= mp4.size &&
            String(mp4, hdlr.body + HDLR_TYPE_AT, TYPE_BYTES, Charsets.ISO_8859_1) == "vide"
    }

    /** A track's width and height as displayed: a quarter-turn matrix, (0, 1, -1, 0) or (0, -1, 1, 0), swaps them. */
    private fun displayedSize(mp4: ByteArray, trak: Box): Pair<Int, Int>? {
        val tkhd = children(mp4, trak.body, trak.end).firstOrNull { it.type == "tkhd" } ?: return null
        // Version 1 widens the times and duration from 4 bytes to 8, which moves the matrix along.
        val matrixAt = tkhd.body + if (mp4[tkhd.body].toInt() == 1) TKHD_V1_MATRIX else TKHD_V0_MATRIX
        if (matrixAt + MATRIX_AND_SIZE > tkhd.end) return null
        val width = (readUInt32(mp4, matrixAt + MATRIX_BYTES) shr FIXED_POINT).toInt()
        val height = (readUInt32(mp4, matrixAt + MATRIX_BYTES + UINT32) shr FIXED_POINT).toInt()
        val quarterTurn = readUInt32(mp4, matrixAt) == 0L && readUInt32(mp4, matrixAt + UINT32) != 0L
        return if (quarterTurn) height to width else width to height
    }

    private data class Box(val type: String, val body: Int, val end: Int)

    /** The boxes from [from] to [to], up to the first that is not a box or does not fit. */
    private fun children(bytes: ByteArray, from: Int, to: Int): List<Box> = buildList {
        var box = boxAt(bytes, from, to)
        while (box != null) {
            add(box)
            box = boxAt(bytes, box.end, to)
        }
    }

    private fun boxAt(bytes: ByteArray, at: Int, to: Int): Box? {
        if (at + HEADER > to) return null
        val type = String(bytes, at + TYPE_AT, TYPE_BYTES, Charsets.ISO_8859_1)
        if (!type.all { it in ' '..'~' }) return null
        val size32 = readUInt32(bytes, at)
        val (size, header) = when {
            size32 == 0L -> (to - at).toLong() to HEADER
            size32 == 1L && at + LARGE_HEADER <= to -> readUInt64(bytes, at + HEADER) to LARGE_HEADER
            else -> size32 to HEADER
        }
        return if (size < header || at + size > to) null else Box(type, at + header, (at + size).toInt())
    }

    private fun readUInt32(bytes: ByteArray, at: Int): Long = readUnsigned(bytes, at, UINT32)

    private fun readUInt64(bytes: ByteArray, at: Int): Long = readUnsigned(bytes, at, UINT64)

    private fun readUnsigned(bytes: ByteArray, at: Int, width: Int): Long = if (at + width > bytes.size) {
        0
    } else {
        (0 until width).fold(0L) { value, index -> (value shl Byte.SIZE_BITS) or (bytes[at + index].toLong() and BYTE_MASK) }
    }

    private const val UINT32 = 4
    private const val UINT64 = 8
    private const val BYTE_MASK = 0xffL
    private const val HEADER = 8
    private const val LARGE_HEADER = 16
    private const val TYPE_AT = 4
    private const val TYPE_BYTES = 4
    private const val MVHD_V0_TIMESCALE = 12
    private const val MVHD_V0_DURATION = 16
    private const val MVHD_V1_TIMESCALE = 20
    private const val MVHD_V1_DURATION = 24
    private const val HDLR_TYPE_AT = 8
    private const val TKHD_V0_MATRIX = 40
    private const val TKHD_V1_MATRIX = 52
    private const val MATRIX_BYTES = 36
    private const val MATRIX_AND_SIZE = 44
    private const val FIXED_POINT = 16
    private const val MICROS_PER_SECOND = 1_000_000L
}

/** The video a motion photo holds, read out of it. */
class MotionPhotoVideo(
    val format: MotionPhotoFormat,
    /** Where the MP4 starts in the file. */
    val offset: Long,
    val bytes: ByteArray,
    val sha256Hex: String,
    val info: Mp4Info,
    val presentationTimestampUs: Long?,
) {
    val byteCount: Long get() = bytes.size.toLong()

    /** When in the video the still was taken: the XMP's presentation time, or the middle of the video without one. */
    val stillTimeUs: Long get() = presentationTimestampUs?.takeIf { it in 0..info.durationUs } ?: (info.durationUs / 2)
}

/** Why a file that looked like a motion photo gave no video; the name goes to diagnostics with the format id. */
enum class MotionPhotoReadFailure { NO_MP4_AT_OFFSET, TOO_LARGE, NO_MOVIE_HEADER }

/**
 * Reads a motion photo's video as the file streams through it once: the start of the file for its XMP, and from where
 * the XMP puts the video, the video itself. Without XMP that describes one, it looks for Samsung's `MotionPhoto_Data`
 * trailer instead. [fileLength] is the file's size, which the XMP's offsets are counted back from.
 */
class MotionPhotoReader(private val fileLength: Long) : OutputStream() {
    private val head = ByteArray(minOf(fileLength, HEAD_BYTES.toLong()).toInt())
    private var headCount = 0
    private var position = 0L
    private var layout: MotionPhotoLayout? = null
    private var layoutRead = false
    private var captureFrom = -1L
    private var capture: ByteArrayOutputStream? = null
    private var tooLarge = false

    // How far the capture already holds the file: what the head held when the layout was read.
    private var copiedUpTo = 0L

    // Samsung's trailer: the marker is matched byte by byte, then what follows it is kept while it is an MP4.
    private var markerMatched = 0
    private var trailerCapture: ByteArrayOutputStream? = null
    private var trailerFrom = -1L
    private var trailerLocked = false

    /** The format the file declared, even when its video could not be read; for diagnostics. */
    var declaredFormat: MotionPhotoFormat? = null
        private set

    /** Why a declared motion photo gave no video, or null. */
    var failure: MotionPhotoReadFailure? = null
        private set

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (headCount < head.size) {
            val copy = minOf(head.size - headCount, length)
            buffer.copyInto(head, headCount, offset, offset + copy)
            headCount += copy
        }
        val start = position
        position += length
        var skip = 0
        if (!layoutRead && (headCount == head.size || MotionPhotoXmp.hasCompletePacket(head, headCount))) {
            readLayout()
            // readLayout scanned the head; only what this write added beyond it is left to scan.
            skip = (headCount - start).coerceIn(0L, length.toLong()).toInt()
        }
        layout?.let { capture(buffer, offset, length, start) }
        if (layoutRead && layout == null) scanTrailer(buffer, offset + skip, offset + length) { index -> start + (index - offset) }
    }

    private fun readLayout() {
        layoutRead = true
        layout = MotionPhotoXmp.findPacket(head, headCount)?.let { MotionPhotoXmp.parse(it, fileLength) }?.also { found ->
            declaredFormat = found.format
            captureFrom = (found.videoOffset - found.slack).coerceAtLeast(0)
            if (fileLength - captureFrom > MAX_VIDEO_BYTES + found.slack) tooLarge = true else capture = ByteArrayOutputStream()
            // The video may start within bytes already read.
            if (!tooLarge && captureFrom < headCount) capture!!.write(head, captureFrom.toInt(), headCount - captureFrom.toInt())
            copiedUpTo = headCount.toLong()
        }
        // Without an XMP layout, the bytes read so far are looked through for Samsung's marker.
        if (layout == null) scanTrailer(head, 0, headCount) { it.toLong() }
    }

    private fun capture(buffer: ByteArray, offset: Int, length: Int, start: Long) {
        val sink = capture ?: return
        val end = start + length
        // Bytes read before the layout was known were copied from the head when it was read.
        val from = maxOf(start, captureFrom, copiedUpTo)
        if (from >= end) return
        sink.write(buffer, offset + (from - start).toInt(), (end - from).toInt())
    }

    /** Looks through [buffer] from [from] to [to] for Samsung's marker; [filePosition] maps an index to the file. */
    private fun scanTrailer(buffer: ByteArray, from: Int, to: Int, filePosition: (Int) -> Long) {
        var index = from
        while (index < to) {
            val sink = trailerCapture
            if (sink != null) {
                // Keep what follows the marker; once it is known to be an MP4, keep the rest whole.
                val copy = if (trailerLocked) to - index else minOf(to - index, FTYP_HEAD - sink.size())
                if (sink.size() + copy > MAX_VIDEO_BYTES) {
                    trailerCapture = null
                    trailerLocked = false
                    tooLarge = true
                    return
                }
                sink.write(buffer, index, copy)
                index += copy
                if (!trailerLocked && sink.size() >= FTYP_HEAD) {
                    if (Mp4Boxes.startsAt(sink.toByteArray(), 0)) trailerLocked = true else trailerCapture = null
                }
                continue
            }
            if (buffer[index] == SAMSUNG_MARKER[markerMatched]) {
                markerMatched += 1
                if (markerMatched == SAMSUNG_MARKER.size) {
                    markerMatched = 0
                    trailerCapture = ByteArrayOutputStream()
                    trailerFrom = filePosition(index) + 1
                }
            } else {
                markerMatched = if (buffer[index] == SAMSUNG_MARKER[0]) 1 else 0
            }
            index += 1
        }
    }

    /** The video, once every byte of the file was written; null when it holds none Alba can read. */
    fun finish(): MotionPhotoVideo? {
        if (!layoutRead) readLayout()
        return layout?.let(::finishLayout) ?: finishTrailer()
    }

    private fun finishLayout(found: MotionPhotoLayout): MotionPhotoVideo? {
        val bytes = capture?.toByteArray()?.takeIf { !tooLarge } ?: return fail(MotionPhotoReadFailure.TOO_LARGE)
        val expected = (found.videoOffset - captureFrom).toInt()
        val start = listOf(expected, expected - found.slack.toInt(), expected + found.slack.toInt())
            .firstOrNull { Mp4Boxes.startsAt(bytes, it) } ?: return fail(MotionPhotoReadFailure.NO_MP4_AT_OFFSET)
        val limit = minOf(bytes.size - start, (found.videoLength + found.slack).toInt())
        return video(found.format, captureFrom + start, bytes, start, limit, found.presentationTimestampUs)
    }

    private fun finishTrailer(): MotionPhotoVideo? {
        if (layout != null) return null
        val trailer = trailerCapture ?: return if (tooLarge) fail(MotionPhotoReadFailure.TOO_LARGE, MotionPhotoFormat.SAMSUNG_TRAILER) else null
        declaredFormat = MotionPhotoFormat.SAMSUNG_TRAILER
        val bytes = trailer.toByteArray()
        if (!Mp4Boxes.startsAt(bytes, 0)) return fail(MotionPhotoReadFailure.NO_MP4_AT_OFFSET)
        return video(MotionPhotoFormat.SAMSUNG_TRAILER, trailerFrom, bytes, 0, bytes.size, null)
    }

    private fun video(format: MotionPhotoFormat, offset: Long, bytes: ByteArray, start: Int, limit: Int, timestamp: Long?): MotionPhotoVideo? {
        val length = Mp4Boxes.length(bytes, start, limit)
        if (length <= 0) return fail(MotionPhotoReadFailure.NO_MP4_AT_OFFSET)
        val mp4 = bytes.copyOfRange(start, start + length)
        val info = Mp4Boxes.info(mp4)?.takeIf { it.durationUs > 0 } ?: return fail(MotionPhotoReadFailure.NO_MOVIE_HEADER)
        val sha = MessageDigest.getInstance("SHA-256").digest(mp4).joinToString("") { "%02x".format(it) }
        return MotionPhotoVideo(format, offset, mp4, sha, info, timestamp)
    }

    private fun fail(reason: MotionPhotoReadFailure, format: MotionPhotoFormat? = null): MotionPhotoVideo? {
        format?.let { declaredFormat = it }
        failure = reason
        return null
    }

    companion object {
        /** How much of the start of a file is kept to find its XMP; Pixel and Samsung write it in the first few KB. */
        const val HEAD_BYTES = 256 * 1024

        /** A motion photo's clip is a few seconds; anything larger is not read into memory. */
        const val MAX_VIDEO_BYTES = 64 * 1024 * 1024
        private val SAMSUNG_MARKER = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

        /** Enough of what follows the marker to see whether an MP4 starts there: its first box header. */
        private const val FTYP_HEAD = 8
    }
}
