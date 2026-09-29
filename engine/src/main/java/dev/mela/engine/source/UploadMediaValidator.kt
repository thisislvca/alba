package dev.mela.engine.source

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Checks the staged original, never a converted preview or a filename extension. */
internal object UploadMediaValidator {
    suspend fun validate(file: File, format: UploadMediaFormat) {
        currentCoroutineContext().ensureActive()
        when (format) {
            UploadMediaFormat.PNG -> inspectChunks(file, png = true)
            UploadMediaFormat.WEBP -> inspectChunks(file, png = false)
            UploadMediaFormat.MP4, UploadMediaFormat.MOV -> {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    val video = tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    val audio = tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    if (video.size != 1 || video.any { it.getString(MediaFormat.KEY_MIME) !in setOf("video/avc", "video/hevc") } ||
                        audio.any { it.getString(MediaFormat.KEY_MIME) != "audio/mp4a-latm" }) {
                        throw UnsupportedUploadException(UnsupportedUploadException.VIDEO_CODEC)
                    }
                    requireValid(video.all { it.getInteger(MediaFormat.KEY_WIDTH) > 0 && it.getInteger(MediaFormat.KEY_HEIGHT) > 0 })
                } catch (error: UnsupportedUploadException) { throw error }
                catch (_: Exception) { throw UnsupportedUploadException(UnsupportedUploadException.INVALID) }
                finally { extractor.release() }
            }
            else -> Unit
        }
        // Bounds only: no full-size pixel allocation, even for a very large original.
        if (format in setOf(UploadMediaFormat.PNG, UploadMediaFormat.HEIC, UploadMediaFormat.WEBP)) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            requireValid(options.outWidth > 0 && options.outHeight > 0)
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun inspectChunks(file: File, png: Boolean) = RandomAccessFile(file, "r").use { input ->
        var cursor = if (png) 8L else 12L
        var imageData = false
        if (!png) {
            input.seek(4)
            requireValid((Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL) + 8L == input.length())
        }
        while (cursor + 8 <= input.length()) {
            currentCoroutineContext().ensureActive()
            input.seek(cursor)
            val length: Long
            val type: String
            if (png) {
                length = input.readInt().toLong() and 0xffffffffL
                type = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
            } else {
                type = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
                length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            }
            val end = cursor + 8 + length + if (png) 4 else length % 2
            requireValid(end <= input.length())
            if (type in setOf("acTL", "fcTL", "fdAT", "ANIM", "ANMF") ||
                (type == "VP8X" && length > 0 && input.readUnsignedByte() and 2 != 0)) {
                throw UnsupportedUploadException(UnsupportedUploadException.ANIMATED)
            }
            if (type in setOf("IDAT", "VP8 ", "VP8L")) imageData = true
            cursor = end
            if (png && type == "IEND") break
        }
        requireValid(imageData && cursor == input.length())
    }

    private fun requireValid(value: Boolean) {
        if (!value) throw UnsupportedUploadException(UnsupportedUploadException.INVALID)
    }
}
