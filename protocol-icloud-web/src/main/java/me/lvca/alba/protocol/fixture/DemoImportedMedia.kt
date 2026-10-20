package me.lvca.alba.protocol.fixture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.lvca.alba.engine.model.MediaKind
import me.lvca.alba.engine.source.MediaRead
import me.lvca.alba.engine.source.RemoteMediaRecord
import me.lvca.alba.engine.source.UploadMediaFormat
import me.lvca.alba.protocol.toHex
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Actual selected bytes in private temporary storage. Never an iCloud upload or a backup receipt. */
internal class DemoImportedMedia(val file: File, private val name: String, private val format: UploadMediaFormat,
    private val width: Int, private val height: Int, private val duration: Long?, private val hash: String) {
    private val video get() = format.mimeType.startsWith("video/")
    fun record(id: String) = RemoteMediaRecord(id, name, file.lastModified(), width, height, hash,
        0xFF607D8B, 0xFF90A4AE, if (video) MediaKind.VIDEO else MediaKind.PHOTO, format.mimeType,
        durationMillis = duration, byteCount = file.length(), addedAtEpochMillis = file.lastModified())

    fun writePreview(output: OutputStream) {
        val bitmap = if (video) MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 560,
                (560L * height / width).toInt().coerceIn(1, 1120))
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(width, height) / inSampleSize > 1120) inSampleSize *= 2
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
        requireNotNull(bitmap) { "Could not preview selected demo media" }
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) } finally { bitmap.recycle() }
    }

    fun openPlayback(position: Long, requested: Long): MediaRead {
        require(video && position in 0..file.length())
        val count = if (requested < 0) file.length() - position else minOf(requested, file.length() - position)
        val stream = file.inputStream()
        stream.channel.position(position)
        return object : MediaRead {
            override val length = count
            override val input = object : java.io.FilterInputStream(stream) {
                private var remaining = count
                override fun read(): Int {
                    if (remaining == 0L) return -1
                    return super.read().also { if (it >= 0) remaining-- }
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0L) return -1
                    return super.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                }
            }
            override fun close() = input.close()
        }
    }

    companion object {
        private const val MAX_FILE = 250L * 1024 * 1024
        suspend fun copy(directory: File, name: String, input: InputStream, expectedBytes: Long?, expectedHash: String?): DemoImportedMedia {
            require(expectedBytes == null || expectedBytes in 1..MAX_FILE)
            check(directory.isDirectory || directory.mkdirs())
            val available = 1024L * 1024 * 1024 - directory.listFiles().orEmpty().sumOf { it.length() }
            require(available > 0) { "Demo storage is full. Restart the app to reset sample files." }
            val file = File.createTempFile("selected-", ".media", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= minOf(MAX_FILE, available)) { "Select a demo file smaller than 250 MB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                val hash = digest.digest().toHex()
                require(expectedBytes == null || total == expectedBytes)
                require(expectedHash == null || hash.equals(expectedHash, ignoreCase = true))
                val format = java.io.RandomAccessFile(file, "r").use { input ->
                    UploadMediaFormat.detect(ByteArray(minOf(4096L, file.length()).toInt()).also(input::readFully))
                }
                var duration: Long? = null
                val (width, height) = if (format.mimeType.startsWith("video/")) MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) to
                        (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)
                } else BitmapFactory.Options().run {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(file.absolutePath, this)
                    outWidth to outHeight
                }
                require(width > 0 && height > 0) { "Invalid selected media" }
                return DemoImportedMedia(file, format.fileName(name), format, width, height, duration, hash)
            } catch (error: Throwable) { file.delete(); throw error }
        }
    }
}
