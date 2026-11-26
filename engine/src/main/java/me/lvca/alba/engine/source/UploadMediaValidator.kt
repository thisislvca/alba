package me.lvca.alba.engine.source

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Checks the staged original, never a converted preview or a filename extension. */
internal object UploadMediaValidator {
    suspend fun validate(file: File, format: UploadMediaFormat) {
        RandomAccessFile(file, "r").use { input ->
            validate(format, input.channel, { setDataSource(file.absolutePath) }) { options ->
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        }
    }

    /**
     * Checks an original through its open, seekable [descriptor], as [validate] does a file. It is read by position and
     * never closed here.
     */
    suspend fun validate(descriptor: FileDescriptor, format: UploadMediaFormat) {
        // Not closed: closing a stream made from a descriptor would close the descriptor.
        validate(format, FileInputStream(descriptor).channel, { setDataSource(descriptor) }) { options ->
            BitmapFactory.decodeFileDescriptor(descriptor, null, options)
        }
    }

    private suspend fun validate(
        format: UploadMediaFormat,
        channel: FileChannel,
        extract: MediaExtractor.() -> Unit,
        decodeBounds: (BitmapFactory.Options) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        when (format) {
            UploadMediaFormat.PNG -> inspectChunks(channel, png = true)
            UploadMediaFormat.WEBP -> inspectChunks(channel, png = false)
            UploadMediaFormat.MP4, UploadMediaFormat.MOV -> inspectTracks(extract)
            else -> Unit
        }
        // Bounds only: no full-size pixel allocation, even for a very large original.
        if (format in setOf(UploadMediaFormat.PNG, UploadMediaFormat.HEIC, UploadMediaFormat.WEBP)) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodeBounds(options)
            requireValid(options.outWidth > 0 && options.outHeight > 0)
        }
        currentCoroutineContext().ensureActive()
    }

    /** A video uploads with exactly one AVC or HEVC track, AAC audio at most, and real dimensions. */
    private fun inspectTracks(extract: MediaExtractor.() -> Unit) {
        val extractor = MediaExtractor()
        val tracks = try {
            extractor.extract()
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        } catch (_: Exception) {
            throw UnsupportedUploadException(UnsupportedUploadException.INVALID)
        } finally {
            extractor.release()
        }
        val video = tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val audio = tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val videoCodecs = video.size == 1 && video.all { it.getString(MediaFormat.KEY_MIME) in setOf("video/avc", "video/hevc") }
        val audioCodecs = audio.all { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
        if (!videoCodecs || !audioCodecs) throw UnsupportedUploadException(UnsupportedUploadException.VIDEO_CODEC)
        val sized = runCatching { video.all { it.getInteger(MediaFormat.KEY_WIDTH) > 0 && it.getInteger(MediaFormat.KEY_HEIGHT) > 0 } }
        requireValid(sized.getOrDefault(false))
    }

    private suspend fun inspectChunks(input: FileChannel, png: Boolean) {
        val length = input.size()
        var cursor = if (png) 8L else 12L
        var imageData = false
        if (!png) {
            requireValid(length >= 8 && (Integer.reverseBytes(input.readAt(4, 4).getInt(0)).toLong() and 0xffffffffL) + 8L == length)
        }
        while (cursor + 8 <= length) {
            currentCoroutineContext().ensureActive()
            val head = input.readAt(cursor, 8)
            val length32: Long
            val type: String
            if (png) {
                length32 = head.getInt(0).toLong() and 0xffffffffL
                type = String(ByteArray(4).also { head.position(4); head.get(it) }, Charsets.US_ASCII)
            } else {
                type = String(ByteArray(4).also { head.position(0); head.get(it) }, Charsets.US_ASCII)
                length32 = Integer.reverseBytes(head.getInt(4)).toLong() and 0xffffffffL
            }
            val end = cursor + 8 + length32 + if (png) 4 else length32 % 2
            requireValid(end <= length)
            val animatedWebp = type == "VP8X" && length32 > 0 && input.readAt(cursor + 8, 1).get(0).toInt() and 2 != 0
            if (type in setOf("acTL", "fcTL", "fdAT", "ANIM", "ANMF") || animatedWebp) {
                throw UnsupportedUploadException(UnsupportedUploadException.ANIMATED)
            }
            if (type in setOf("IDAT", "VP8 ", "VP8L")) imageData = true
            cursor = end
            if (png && type == "IEND") break
        }
        requireValid(imageData && cursor == length)
    }

    private fun requireValid(value: Boolean) {
        if (!value) throw UnsupportedUploadException(UnsupportedUploadException.INVALID)
    }
}
